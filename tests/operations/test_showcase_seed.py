from __future__ import annotations

import json
import os
import subprocess
import tempfile
import unittest
from pathlib import Path
from unittest import mock

from tools.operations import showcase_seed
from tools.operations.postgres_common import OperationSafetyError


class ShowcaseSeedTests(unittest.TestCase):
    def setUp(self) -> None:
        self.temp_directory = tempfile.TemporaryDirectory()
        self.tmp_path = Path(self.temp_directory.name)

    def tearDown(self) -> None:
        self.temp_directory.cleanup()

    def files(self) -> tuple[Path, Path]:
        compose_file = self.tmp_path / "compose.yml"
        compose_file.write_text("services: {}\n", encoding="utf-8")
        sql_file = self.tmp_path / "seed.sql"
        sql_file.write_text("SELECT 1;\n", encoding="utf-8")
        return compose_file, sql_file

    def build_plan(self) -> showcase_seed.ShowcaseSeedPlan:
        compose_file, sql_file = self.files()
        return showcase_seed.build_plan(
            compose_file=compose_file,
            database="guanxian",
            user="postgres",
            sql_file=sql_file,
        )

    def test_build_plan_targets_fixed_postgres_service(self) -> None:
        plan = self.build_plan()
        self.assertEqual(
            plan.command[:4],
            (
                "docker",
                "compose",
                "--file",
                str((self.tmp_path / "compose.yml").resolve()),
            ),
        )
        self.assertEqual(plan.command[4:7], ("exec", "--no-TTY", "postgres"))
        self.assertEqual(plan.command[-1], "--file=-")

    def test_build_plan_rejects_other_database(self) -> None:
        compose_file, sql_file = self.files()
        with self.assertRaisesRegex(OperationSafetyError, "supports only"):
            showcase_seed.build_plan(
                compose_file=compose_file,
                database="keycloak",
                user="postgres",
                sql_file=sql_file,
            )

    def test_build_plan_accepts_isolated_restore_database(self) -> None:
        compose_file, sql_file = self.files()
        plan = showcase_seed.build_plan(
            compose_file=compose_file,
            database="guanxian_restore_test_showcase",
            user="postgres",
            sql_file=sql_file,
        )
        self.assertIn("expected_database=guanxian_restore_test_showcase", plan.command)

    def test_execution_guard_requires_both_confirmations(self) -> None:
        with mock.patch.dict(os.environ, {}, clear=False):
            os.environ.pop(showcase_seed.EXECUTION_GUARD_NAME, None)
            with self.assertRaisesRegex(OperationSafetyError, "confirm-database"):
                showcase_seed.validate_execution_guard("guanxian", None)
            with self.assertRaisesRegex(
                OperationSafetyError, showcase_seed.EXECUTION_GUARD_NAME
            ):
                showcase_seed.validate_execution_guard("guanxian", "guanxian")

    def test_execute_passes_sql_on_stdin_and_verifies_counts(self) -> None:
        plan = self.build_plan()
        result = {
            "status": "verified",
            "operation": "showcase-seed",
            "label": "【演示】",
            **showcase_seed.EXPECTED_RECORDS,
        }
        completed = subprocess.CompletedProcess(
            plan.command,
            0,
            stdout=(json.dumps(result, ensure_ascii=False) + "\n").encode(),
            stderr=b"",
        )
        with mock.patch.object(
            showcase_seed.subprocess, "run", return_value=completed
        ) as run:
            self.assertEqual(showcase_seed.execute_seed(plan), result)
        self.assertEqual(run.call_args.kwargs["input"], plan.sql_file.read_bytes())
        self.assertFalse(run.call_args.kwargs["check"])

    def test_execute_rejects_unverified_count(self) -> None:
        plan = self.build_plan()
        result = {
            "status": "verified",
            **showcase_seed.EXPECTED_RECORDS,
            "matches": 5,
        }
        completed = subprocess.CompletedProcess(
            plan.command,
            0,
            stdout=(json.dumps(result) + "\n").encode(),
            stderr=b"",
        )
        with mock.patch.object(showcase_seed.subprocess, "run", return_value=completed):
            with self.assertRaisesRegex(RuntimeError, "matches"):
                showcase_seed.execute_seed(plan)


if __name__ == "__main__":
    unittest.main()
