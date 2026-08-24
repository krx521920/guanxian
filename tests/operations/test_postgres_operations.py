from __future__ import annotations

import json
import subprocess
import tempfile
import unittest
from datetime import datetime, timezone
from pathlib import Path
from unittest import mock

from tools.operations import postgres_backup, postgres_restore_drill
from tools.operations.postgres_common import (
    EXECUTION_GUARD_NAME,
    EXECUTION_GUARD_VALUE,
    OperationSafetyError,
    sha256_file,
    validate_identifier,
    validate_restore_target,
)


class OperationFixture(unittest.TestCase):
    def setUp(self) -> None:
        self.temporary_directory = tempfile.TemporaryDirectory()
        self.root = Path(self.temporary_directory.name)
        self.compose_file = self.root / "compose.yaml"
        self.compose_file.write_text("services:\n  postgres:\n    image: postgres:16-alpine\n", encoding="utf-8")

    def tearDown(self) -> None:
        self.temporary_directory.cleanup()

    def create_archive(self, content: bytes = b"valid custom-format fixture") -> Path:
        archive = self.root / "guanxian-20260824T010203Z.dump"
        archive.write_bytes(content)
        manifest = {
            "schemaVersion": 1,
            "createdAt": "2026-08-24T01:02:03+00:00",
            "database": "guanxian",
            "archiveFilename": archive.name,
            "sizeBytes": len(content),
            "sha256": sha256_file(archive),
            "format": "pg_dump-custom",
        }
        archive.with_suffix(archive.suffix + ".manifest.json").write_text(
            json.dumps(manifest), encoding="utf-8"
        )
        return archive


class ParameterSafetyTests(OperationFixture):
    def test_accepts_strict_lowercase_database_identifier(self) -> None:
        self.assertEqual("guanxian_2026", validate_identifier("guanxian_2026", "database"))

    def test_rejects_shell_and_sql_metacharacters(self) -> None:
        for value in ("guanxian;drop", "guanxian-prod", "guanxian db", 'guanxian"', "guanxian\nother"):
            with self.subTest(value=value), self.assertRaises(OperationSafetyError):
                validate_identifier(value, "database")

    def test_rejects_uppercase_and_overlength_identifiers(self) -> None:
        for value in ("Guanxian", "a" * 64):
            with self.subTest(value=value), self.assertRaises(OperationSafetyError):
                validate_identifier(value, "database")

    def test_restore_target_must_use_isolated_test_prefix_and_suffix(self) -> None:
        for value in ("guanxian", "guanxian_restore", "guanxian_restore_test_"):
            with self.subTest(value=value), self.assertRaises(OperationSafetyError):
                validate_restore_target(value, "guanxian")
        self.assertEqual(
            "guanxian_restore_test_drill01",
            validate_restore_target("guanxian_restore_test_drill01", "guanxian"),
        )

    def test_restore_target_can_never_equal_configured_production_database(self) -> None:
        with self.assertRaises(OperationSafetyError):
            validate_restore_target("guanxian_restore_test_live", "guanxian_restore_test_live")


class BackupTests(OperationFixture):
    def build_plan(self) -> postgres_backup.BackupPlan:
        return postgres_backup.build_backup_plan(
            compose_file=self.compose_file,
            output_dir=self.root / "backups",
            database="guanxian",
            user="guanxian",
            timestamp=datetime(2026, 8, 24, 1, 2, 3, tzinfo=timezone.utc),
        )

    def test_backup_plan_uses_argument_array_and_fixed_postgres_service(self) -> None:
        plan = self.build_plan()
        self.assertIsInstance(plan.command, tuple)
        self.assertEqual("postgres", plan.command[6])
        self.assertEqual("pg_dump", plan.command[7])
        self.assertNotIn(";", "".join(plan.command))

    def test_backup_dry_run_never_invokes_subprocess_or_creates_output(self) -> None:
        output_dir = self.root / "backups"
        with mock.patch.object(postgres_backup.subprocess, "run") as run:
            result = postgres_backup.main(
                [
                    "--compose-file",
                    str(self.compose_file),
                    "--output-dir",
                    str(output_dir),
                    "--dry-run",
                ]
            )
        self.assertEqual(0, result)
        run.assert_not_called()
        self.assertFalse(output_dir.exists())

    def test_backup_is_atomic_and_writes_integrity_manifest(self) -> None:
        plan = self.build_plan()

        def fake_run(command, **kwargs):
            self.assertNotIn("shell", kwargs)
            kwargs["stdout"].write(b"pg-custom-data")
            return subprocess.CompletedProcess(command, 0, stdout=None, stderr=b"")

        with mock.patch.object(postgres_backup.subprocess, "run", side_effect=fake_run):
            manifest = postgres_backup.execute_backup(
                plan, created_at=datetime(2026, 8, 24, 1, 2, 4, tzinfo=timezone.utc)
            )
        self.assertTrue(plan.archive.is_file())
        self.assertTrue(plan.manifest.is_file())
        self.assertFalse(plan.partial_archive.exists())
        self.assertEqual(sha256_file(plan.archive), manifest["sha256"])
        self.assertNotIn("password", json.dumps(manifest).lower())

    def test_backup_refuses_to_overwrite_existing_archive(self) -> None:
        plan = self.build_plan()
        plan.archive.parent.mkdir(parents=True)
        plan.archive.write_bytes(b"existing")
        with self.assertRaises(OperationSafetyError), mock.patch.object(
            postgres_backup.subprocess, "run"
        ) as run:
            postgres_backup.execute_backup(plan)
        run.assert_not_called()

    def test_failed_backup_removes_partial_archive(self) -> None:
        plan = self.build_plan()
        completed = subprocess.CompletedProcess(plan.command, 4, stdout=None, stderr=b"safe failure")
        with mock.patch.object(postgres_backup.subprocess, "run", return_value=completed):
            with self.assertRaises(RuntimeError):
                postgres_backup.execute_backup(plan)
        self.assertFalse(plan.partial_archive.exists())
        self.assertFalse(plan.archive.exists())


class RestoreDrillTests(OperationFixture):
    def build_plan(self) -> postgres_restore_drill.RestorePlan:
        return postgres_restore_drill.build_restore_plan(
            compose_file=self.compose_file,
            archive=self.create_archive(),
            target_database="guanxian_restore_test_drill01",
            production_database="guanxian",
            user="guanxian",
        )

    def test_restore_rejects_archive_tampering(self) -> None:
        archive = self.create_archive()
        archive.write_bytes(b"tampered")
        with self.assertRaises(OperationSafetyError):
            postgres_restore_drill.build_restore_plan(
                compose_file=self.compose_file,
                archive=archive,
                target_database="guanxian_restore_test_drill01",
                production_database="guanxian",
                user="guanxian",
            )

    def test_restore_rejects_malformed_manifest_integrity_fields(self) -> None:
        archive = self.create_archive()
        manifest_path = archive.with_suffix(archive.suffix + ".manifest.json")
        manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
        manifest["sizeBytes"] = True
        manifest_path.write_text(json.dumps(manifest), encoding="utf-8")
        with self.assertRaises(OperationSafetyError):
            postgres_restore_drill.load_and_verify_manifest(archive)

    def test_restore_dry_run_is_default_and_never_invokes_subprocess(self) -> None:
        archive = self.create_archive()
        with mock.patch.object(postgres_restore_drill.subprocess, "run") as run:
            result = postgres_restore_drill.main(
                [
                    "--compose-file",
                    str(self.compose_file),
                    "--archive",
                    str(archive),
                    "--target-database",
                    "guanxian_restore_test_drill01",
                ]
            )
        self.assertEqual(0, result)
        run.assert_not_called()

    def test_restore_execute_requires_exact_confirmation(self) -> None:
        plan = self.build_plan()
        with self.assertRaises(OperationSafetyError):
            postgres_restore_drill.validate_execution_guards(
                plan,
                confirm_target="guanxian_restore_test_other",
                environment={EXECUTION_GUARD_NAME: EXECUTION_GUARD_VALUE},
            )

    def test_restore_execute_requires_environment_guard(self) -> None:
        plan = self.build_plan()
        with self.assertRaises(OperationSafetyError):
            postgres_restore_drill.validate_execution_guards(
                plan, confirm_target=plan.target_database, environment={}
            )

    def test_destructive_restore_function_enforces_guards_before_subprocess(self) -> None:
        plan = self.build_plan()
        with self.assertRaises(OperationSafetyError), mock.patch.object(
            postgres_restore_drill.subprocess, "run"
        ) as run:
            postgres_restore_drill.execute_restore(
                plan, confirm_target=plan.target_database, environment={}
            )
        run.assert_not_called()

    def test_restore_commands_use_fixed_service_and_validated_target(self) -> None:
        plan = self.build_plan()
        for command in (
            plan.drop_command,
            plan.create_command,
            plan.restore_command,
            plan.verify_command,
        ):
            self.assertEqual("postgres", command[6])
            self.assertNotIn("shell", command)
        self.assertIn(plan.target_database, plan.drop_command)
        self.assertIn(plan.target_database, plan.restore_command)

    def test_restore_executes_only_after_both_guards_and_verifies_flyway(self) -> None:
        plan = self.build_plan()
        observed: list[tuple[str, ...]] = []

        def fake_run(command, **kwargs):
            self.assertNotIn("shell", kwargs)
            observed.append(command)
            stdout = b"1\n" if command == plan.verify_command else b""
            return subprocess.CompletedProcess(command, 0, stdout=stdout, stderr=b"")

        with mock.patch.object(postgres_restore_drill.subprocess, "run", side_effect=fake_run):
            postgres_restore_drill.execute_restore(
                plan,
                confirm_target=plan.target_database,
                environment={EXECUTION_GUARD_NAME: EXECUTION_GUARD_VALUE},
            )
        self.assertEqual(
            [plan.drop_command, plan.create_command, plan.restore_command, plan.verify_command],
            observed,
        )

    def test_failed_restore_cleans_up_only_the_validated_test_database(self) -> None:
        plan = self.build_plan()
        observed: list[tuple[str, ...]] = []

        def fake_run(command, **kwargs):
            observed.append(command)
            return_code = 1 if command == plan.restore_command else 0
            return subprocess.CompletedProcess(command, return_code, stdout=b"", stderr=b"restore failed")

        with mock.patch.object(postgres_restore_drill.subprocess, "run", side_effect=fake_run):
            with self.assertRaises(RuntimeError):
                postgres_restore_drill.execute_restore(
                    plan,
                    confirm_target=plan.target_database,
                    environment={EXECUTION_GUARD_NAME: EXECUTION_GUARD_VALUE},
                )
        self.assertEqual(
            [plan.drop_command, plan.create_command, plan.restore_command, plan.drop_command],
            observed,
        )


if __name__ == "__main__":
    unittest.main()
