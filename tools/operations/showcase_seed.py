#!/usr/bin/env python3
"""Seed a guarded, idempotent showcase dataset into the Guanxian database."""

from __future__ import annotations

import argparse
import json
import os
import subprocess
import sys
from dataclasses import dataclass
from pathlib import Path
from typing import Sequence

try:
    from tools.operations.postgres_common import (
        OperationSafetyError,
        RESTORE_TARGET_PREFIX,
        compose_command,
        repository_root,
        resolve_existing_file,
        validate_identifier,
    )
except ModuleNotFoundError:  # Supports direct execution from outside the repository root.
    from postgres_common import (  # type: ignore[no-redef]
        OperationSafetyError,
        RESTORE_TARGET_PREFIX,
        compose_command,
        repository_root,
        resolve_existing_file,
        validate_identifier,
    )


EXECUTION_GUARD_NAME = "GUANXIAN_ALLOW_SHOWCASE_SEED"
EXECUTION_GUARD_VALUE = "I_UNDERSTAND_THIS_WRITES_DEMO_DATA"
PRODUCTION_DATABASE = "guanxian"
EXPECTED_RECORDS = {
    "offerings": 5,
    "demands": 4,
    "matches": 6,
    "collaborations": 4,
    "policies": 3,
    "policyImpacts": 4,
    "knowledgeDocuments": 2,
}


@dataclass(frozen=True)
class ShowcaseSeedPlan:
    database: str
    sql_file: Path
    command: tuple[str, ...]


def build_plan(*, compose_file: Path, database: str, user: str, sql_file: Path) -> ShowcaseSeedPlan:
    compose_file = resolve_existing_file(compose_file, "compose file")
    sql_file = resolve_existing_file(sql_file, "showcase seed SQL file")
    database = validate_identifier(database, "database")
    user = validate_identifier(user, "database user")
    if database != PRODUCTION_DATABASE and not database.startswith(RESTORE_TARGET_PREFIX):
        raise OperationSafetyError(
            f"showcase seed supports only {PRODUCTION_DATABASE!r} or an isolated "
            f"{RESTORE_TARGET_PREFIX!r} database"
        )
    return ShowcaseSeedPlan(
        database=database,
        sql_file=sql_file,
        command=compose_command(
            compose_file,
            (
                "psql",
                "--username",
                user,
                "--dbname",
                database,
                "--set",
                "ON_ERROR_STOP=1",
                "--set",
                f"expected_database={database}",
                "--no-psqlrc",
                "--quiet",
                "--tuples-only",
                "--no-align",
                "--file=-",
            ),
        ),
    )


def describe_plan(plan: ShowcaseSeedPlan) -> dict[str, object]:
    return {
        "mode": "dry-run",
        "operation": "showcase-seed",
        "database": plan.database,
        "sqlFile": str(plan.sql_file),
        "label": "【演示】",
        "expectedRecords": EXPECTED_RECORDS,
        "command": list(plan.command),
        "note": "No data was written. Use --execute with the explicit environment guard.",
    }


def validate_execution_guard(database: str, confirm_database: str | None) -> None:
    if confirm_database != database:
        raise OperationSafetyError(
            f"--confirm-database must exactly equal {database!r}"
        )
    if os.getenv(EXECUTION_GUARD_NAME) != EXECUTION_GUARD_VALUE:
        raise OperationSafetyError(
            f"set {EXECUTION_GUARD_NAME}={EXECUTION_GUARD_VALUE} before --execute"
        )


def execute_seed(plan: ShowcaseSeedPlan) -> dict[str, object]:
    completed = subprocess.run(
        plan.command,
        input=plan.sql_file.read_bytes(),
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        check=False,
    )
    stdout = completed.stdout.decode("utf-8", errors="replace").strip()
    if completed.returncode != 0:
        detail = completed.stderr.decode("utf-8", errors="replace")[-2000:].strip()
        raise RuntimeError(
            f"showcase seed failed with exit code {completed.returncode}: {detail}"
        )
    lines = [line.strip() for line in stdout.splitlines() if line.strip()]
    if not lines:
        raise RuntimeError("showcase seed returned no verification result")
    try:
        result = json.loads(lines[-1])
    except json.JSONDecodeError as error:
        raise RuntimeError("showcase seed returned an invalid verification result") from error
    if result.get("status") != "verified":
        raise RuntimeError("showcase seed did not report verified status")
    for key, expected in EXPECTED_RECORDS.items():
        if result.get(key) != expected:
            raise RuntimeError(
                f"showcase seed verification mismatch for {key}: "
                f"expected {expected}, got {result.get(key)!r}"
            )
    return result


def parse_args(argv: Sequence[str] | None = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--compose-file",
        type=Path,
        default=repository_root() / "compose.production.yml",
        help="production Docker Compose file containing the fixed 'postgres' service",
    )
    parser.add_argument(
        "--sql-file",
        type=Path,
        default=Path(__file__).with_suffix(".sql"),
        help="version-controlled SQL dataset",
    )
    parser.add_argument("--database", default=PRODUCTION_DATABASE)
    parser.add_argument("--user", default=os.getenv("POSTGRES_USER", "postgres"))
    parser.add_argument(
        "--confirm-database",
        help="required for --execute; must exactly equal the selected --database",
    )
    parser.add_argument(
        "--execute",
        action="store_true",
        help="write the idempotent showcase dataset after all guards pass",
    )
    return parser.parse_args(argv)


def main(argv: Sequence[str] | None = None) -> int:
    args = parse_args(argv)
    try:
        plan = build_plan(
            compose_file=args.compose_file,
            database=args.database,
            user=args.user,
            sql_file=args.sql_file,
        )
        if not args.execute:
            print(json.dumps(describe_plan(plan), ensure_ascii=False, indent=2))
            return 0
        validate_execution_guard(plan.database, args.confirm_database)
        print(json.dumps(execute_seed(plan), ensure_ascii=False, indent=2))
        return 0
    except (OperationSafetyError, OSError, RuntimeError) as error:
        print(f"ERROR: {error}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
