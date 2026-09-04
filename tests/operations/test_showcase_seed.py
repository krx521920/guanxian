from __future__ import annotations

import json
import subprocess
from pathlib import Path
from unittest import mock

import pytest

from tools.operations import showcase_seed
from tools.operations.postgres_common import OperationSafetyError


def files(tmp_path: Path) -> tuple[Path, Path]:
    compose_file = tmp_path / "compose.yml"
    compose_file.write_text("services: {}\n", encoding="utf-8")
    sql_file = tmp_path / "seed.sql"
    sql_file.write_text("SELECT 1;\n", encoding="utf-8")
    return compose_file, sql_file


def build_plan(tmp_path: Path) -> showcase_seed.ShowcaseSeedPlan:
    compose_file, sql_file = files(tmp_path)
    return showcase_seed.build_plan(
        compose_file=compose_file,
        database="guanxian",
        user="postgres",
        sql_file=sql_file,
    )


def test_build_plan_targets_fixed_postgres_service(tmp_path: Path) -> None:
    plan = build_plan(tmp_path)
    assert plan.command[:4] == (
        "docker",
        "compose",
        "--file",
        str((tmp_path / "compose.yml").resolve()),
    )
    assert plan.command[4:7] == ("exec", "--no-TTY", "postgres")
    assert plan.command[-1] == "--file=-"


def test_build_plan_rejects_other_database(tmp_path: Path) -> None:
    compose_file, sql_file = files(tmp_path)
    with pytest.raises(OperationSafetyError, match="supports only"):
        showcase_seed.build_plan(
            compose_file=compose_file,
            database="keycloak",
            user="postgres",
            sql_file=sql_file,
        )


def test_build_plan_accepts_isolated_restore_database(tmp_path: Path) -> None:
    compose_file, sql_file = files(tmp_path)
    plan = showcase_seed.build_plan(
        compose_file=compose_file,
        database="guanxian_restore_test_showcase",
        user="postgres",
        sql_file=sql_file,
    )
    assert "expected_database=guanxian_restore_test_showcase" in plan.command


def test_execution_guard_requires_both_confirmations(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.delenv(showcase_seed.EXECUTION_GUARD_NAME, raising=False)
    with pytest.raises(OperationSafetyError, match="confirm-database"):
        showcase_seed.validate_execution_guard("guanxian", None)
    with pytest.raises(OperationSafetyError, match=showcase_seed.EXECUTION_GUARD_NAME):
        showcase_seed.validate_execution_guard("guanxian", "guanxian")


def test_execute_passes_sql_on_stdin_and_verifies_counts(tmp_path: Path) -> None:
    plan = build_plan(tmp_path)
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
    with mock.patch.object(showcase_seed.subprocess, "run", return_value=completed) as run:
        assert showcase_seed.execute_seed(plan) == result
    assert run.call_args.kwargs["input"] == plan.sql_file.read_bytes()
    assert run.call_args.kwargs["check"] is False


def test_execute_rejects_unverified_count(tmp_path: Path) -> None:
    plan = build_plan(tmp_path)
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
        with pytest.raises(RuntimeError, match="matches"):
            showcase_seed.execute_seed(plan)
