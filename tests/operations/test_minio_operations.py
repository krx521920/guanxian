from __future__ import annotations

import argparse
import json
import subprocess
import tempfile
import unittest
from pathlib import Path
from unittest import mock

from tools.operations import minio_backup, minio_restore_drill
from tools.operations.minio_common import (
    EXECUTION_GUARD_NAME,
    EXECUTION_GUARD_VALUE,
    MinioOperationError,
    snapshot_files,
    validate_bucket,
    validate_endpoint,
    verify_snapshot,
)


class MinioOperationTests(unittest.TestCase):
    def setUp(self) -> None:
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        self.access = self.root / "access"
        self.secret = self.root / "secret"
        self.access.write_text("test-access", encoding="utf-8")
        self.secret.write_text("test-secret-value", encoding="utf-8")

    def tearDown(self) -> None:
        self.temp.cleanup()

    def snapshot(self) -> Path:
        root = self.root / "snapshot"
        data = root / "data"
        data.mkdir(parents=True)
        (data / "nested").mkdir()
        (data / "nested" / "source.txt").write_text("source evidence", encoding="utf-8")
        manifest = {
            "schemaVersion": 1,
            "operation": "minio-backup",
            "createdAt": "2026-09-02T00:00:00+00:00",
            "bucket": "guanxian-private",
            "files": snapshot_files(data),
        }
        (root / "manifest.json").write_text(json.dumps(manifest), encoding="utf-8")
        return root

    def test_endpoint_and_restore_bucket_are_fail_closed(self) -> None:
        self.assertEqual("https://storage.internal", validate_endpoint("https://storage.internal/"))
        for endpoint in ("http://storage.internal", "https://user:pass@storage.internal", "https://storage.internal/path"):
            with self.subTest(endpoint=endpoint), self.assertRaises(MinioOperationError):
                validate_endpoint(endpoint)
        with self.assertRaises(MinioOperationError):
            validate_bucket("production-data", restore=True)
        self.assertEqual(
            "guanxian-restore-test-drill01",
            validate_bucket("guanxian-restore-test-drill01", restore=True),
        )

    def test_snapshot_detects_modified_object(self) -> None:
        snapshot = self.snapshot()
        (snapshot / "data" / "nested" / "source.txt").write_text("tampered", encoding="utf-8")
        with self.assertRaises(MinioOperationError):
            verify_snapshot(snapshot)

    def test_backup_command_never_contains_credentials(self) -> None:
        args = argparse.Namespace(
            endpoint="https://storage.internal",
            bucket="guanxian-private",
            access_key_file=self.access,
            secret_key_file=self.secret,
            output_dir=self.root / "backups",
            mc="mc",
        )

        def fake_run(command, **kwargs):
            self.assertNotIn("test-access", command)
            self.assertNotIn("test-secret-value", command)
            destination = Path(command[-1])
            (destination / "proof.txt").write_text("object", encoding="utf-8")
            return subprocess.CompletedProcess(command, 0, stdout=b"", stderr=b"")

        with mock.patch.object(minio_backup.subprocess, "run", side_effect=fake_run):
            result = minio_backup.execute(args)
        self.assertEqual("created", result["status"])
        verify_snapshot(Path(result["snapshot"]))

    def test_restore_requires_both_guards_and_verifies_empty_diff(self) -> None:
        args = argparse.Namespace(
            snapshot=self.snapshot(),
            endpoint="https://storage.internal",
            target_bucket="guanxian-restore-test-drill01",
            confirm_target="guanxian-restore-test-drill01",
            access_key_file=self.access,
            secret_key_file=self.secret,
            mc="mc",
        )
        with mock.patch.dict("os.environ", {}, clear=True), self.assertRaises(MinioOperationError):
            minio_restore_drill.execute(args)

        observed: list[list[str]] = []
        def fake_run(command, **kwargs):
            observed.append(command)
            return subprocess.CompletedProcess(command, 0, stdout=b"", stderr=b"")

        with mock.patch.dict("os.environ", {EXECUTION_GUARD_NAME: EXECUTION_GUARD_VALUE}, clear=True), mock.patch.object(
            minio_restore_drill.subprocess, "run", side_effect=fake_run
        ):
            report = minio_restore_drill.execute(args)
        self.assertEqual(["mb", "mirror", "diff"], [command[1] for command in observed])
        self.assertEqual("verified", report["status"])
        self.assertTrue(report["verification"]["inventoryAndSha256Verified"])


if __name__ == "__main__":
    unittest.main()
