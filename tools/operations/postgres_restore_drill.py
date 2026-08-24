#!/usr/bin/env python3
"""Verify a PostgreSQL backup by restoring it into an isolated test database."""

from __future__ import annotations

import argparse
import json
import os
import re
import subprocess
import sys
from dataclasses import dataclass
from pathlib import Path
from typing import Mapping, Sequence

try:
    from tools.operations.postgres_common import (
        EXECUTION_GUARD_NAME,
        EXECUTION_GUARD_VALUE,
        OperationSafetyError,
        compose_command,
        repository_root,
        resolve_existing_file,
        sha256_file,
        validate_identifier,
        validate_restore_target,
    )
except ModuleNotFoundError:  # Supports direct execution from outside the repository root.
    from postgres_common import (  # type: ignore[no-redef]
        EXECUTION_GUARD_NAME,
        EXECUTION_GUARD_VALUE,
        OperationSafetyError,
        compose_command,
        repository_root,
        resolve_existing_file,
        sha256_file,
        validate_identifier,
        validate_restore_target,
    )


@dataclass(frozen=True)
class RestorePlan:
    archive: Path
    manifest: Path
    source_database: str
    target_database: str
    drop_command: tuple[str, ...]
    create_command: tuple[str, ...]
    restore_command: tuple[str, ...]
    verify_command: tuple[str, ...]


def load_and_verify_manifest(archive: Path) -> tuple[Path, dict[str, object]]:
    archive = resolve_existing_file(archive, "backup archive")
    manifest_path = resolve_existing_file(
        archive.with_suffix(archive.suffix + ".manifest.json"), "backup manifest"
    )
    try:
        manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    except (json.JSONDecodeError, UnicodeError) as error:
        raise OperationSafetyError(f"backup manifest is not valid UTF-8 JSON: {error}") from error

    if not isinstance(manifest, dict) or manifest.get("schemaVersion") != 1:
        raise OperationSafetyError("backup manifest schemaVersion must be 1")
    if manifest.get("archiveFilename") != archive.name:
        raise OperationSafetyError("backup manifest archive filename does not match the selected archive")
    if manifest.get("format") != "pg_dump-custom":
        raise OperationSafetyError("backup manifest format must be pg_dump-custom")
    expected_size = manifest.get("sizeBytes")
    if isinstance(expected_size, bool) or not isinstance(expected_size, int) or expected_size <= 0:
        raise OperationSafetyError("backup manifest sizeBytes must be a positive integer")
    if expected_size != archive.stat().st_size:
        raise OperationSafetyError("backup archive size does not match its manifest")
    expected_sha256 = manifest.get("sha256")
    if not isinstance(expected_sha256, str) or not re.fullmatch(r"[0-9a-f]{64}", expected_sha256):
        raise OperationSafetyError("backup manifest SHA-256 must be 64 lowercase hexadecimal characters")
    if expected_sha256 != sha256_file(archive):
        raise OperationSafetyError("backup archive SHA-256 does not match its manifest")
    source_database = manifest.get("database")
    if not isinstance(source_database, str):
        raise OperationSafetyError("backup manifest database is missing")
    validate_identifier(source_database, "manifest database")
    return manifest_path, manifest


def build_restore_plan(
    *,
    compose_file: Path,
    archive: Path,
    target_database: str,
    production_database: str,
    user: str,
) -> RestorePlan:
    compose_file = resolve_existing_file(compose_file, "compose file")
    target_database = validate_restore_target(target_database, production_database)
    user = validate_identifier(user, "database user")
    manifest_path, manifest = load_and_verify_manifest(archive)
    archive = archive.expanduser().resolve()
    source_database = str(manifest["database"])
    return RestorePlan(
        archive=archive,
        manifest=manifest_path,
        source_database=source_database,
        target_database=target_database,
        drop_command=compose_command(
            compose_file,
            ("dropdb", "--if-exists", "--force", "--username", user, target_database),
        ),
        create_command=compose_command(
            compose_file,
            ("createdb", "--username", user, "--template", "template0", target_database),
        ),
        restore_command=compose_command(
            compose_file,
            (
                "pg_restore",
                "--exit-on-error",
                "--no-owner",
                "--no-privileges",
                "--username",
                user,
                "--dbname",
                target_database,
            ),
        ),
        verify_command=compose_command(
            compose_file,
            (
                "psql",
                "--username",
                user,
                "--dbname",
                target_database,
                "--tuples-only",
                "--no-align",
                "--command",
                "SELECT CASE WHEN to_regclass('public.flyway_schema_history') IS NULL THEN 0 ELSE 1 END",
            ),
        ),
    )


def describe_plan(plan: RestorePlan) -> dict[str, object]:
    return {
        "mode": "dry-run",
        "operation": "postgres-restore-drill",
        "archive": str(plan.archive),
        "manifest": str(plan.manifest),
        "sourceDatabase": plan.source_database,
        "targetDatabase": plan.target_database,
        "commands": [
            list(plan.drop_command),
            list(plan.create_command),
            list(plan.restore_command),
            list(plan.verify_command),
        ],
        "note": "No command was executed. --execute is guarded and only supports isolated test targets.",
    }


def validate_execution_guards(
    plan: RestorePlan, *, confirm_target: str | None, environment: Mapping[str, str]
) -> None:
    if confirm_target != plan.target_database:
        raise OperationSafetyError("--confirm-target must exactly match the isolated test target")
    if environment.get(EXECUTION_GUARD_NAME) != EXECUTION_GUARD_VALUE:
        raise OperationSafetyError(
            f"set {EXECUTION_GUARD_NAME}={EXECUTION_GUARD_VALUE} to execute a destructive test restore"
        )


def _run(command: tuple[str, ...], *, stdin=None, capture_stdout: bool = False) -> subprocess.CompletedProcess[bytes]:
    completed = subprocess.run(
        command,
        stdin=stdin,
        stdout=subprocess.PIPE if capture_stdout else subprocess.DEVNULL,
        stderr=subprocess.PIPE,
        check=False,
    )
    if completed.returncode != 0:
        detail = completed.stderr.decode("utf-8", errors="replace")[-1000:].strip()
        raise RuntimeError(f"database command failed with exit code {completed.returncode}: {detail}")
    return completed


def execute_restore(plan: RestorePlan) -> None:
    _run(plan.drop_command)
    try:
        _run(plan.create_command)
        with plan.archive.open("rb") as archive:
            _run(plan.restore_command, stdin=archive)
        verification = _run(plan.verify_command, capture_stdout=True)
        if verification.stdout.decode("utf-8", errors="replace").strip() != "1":
            raise RuntimeError("restore verification failed: Flyway schema history table was not found")
    except Exception:
        try:
            _run(plan.drop_command)
        except RuntimeError:
            pass
        raise


def parse_args(argv: Sequence[str] | None = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--archive", type=Path, required=True)
    parser.add_argument("--target-database", required=True)
    parser.add_argument("--confirm-target")
    parser.add_argument(
        "--compose-file",
        type=Path,
        default=repository_root() / "compose.yaml",
        help="Docker Compose file containing the fixed 'postgres' service",
    )
    parser.add_argument("--production-database", default=os.getenv("POSTGRES_DB", "guanxian"))
    parser.add_argument("--user", default=os.getenv("POSTGRES_USER", "guanxian"))
    mode = parser.add_mutually_exclusive_group()
    mode.add_argument("--dry-run", action="store_true", help="validate and print the plan; this is the default")
    mode.add_argument("--execute", action="store_true", help="run the guarded restore drill")
    return parser.parse_args(argv)


def main(argv: Sequence[str] | None = None) -> int:
    args = parse_args(argv)
    try:
        plan = build_restore_plan(
            compose_file=args.compose_file,
            archive=args.archive,
            target_database=args.target_database,
            production_database=args.production_database,
            user=args.user,
        )
        if not args.execute:
            print(json.dumps(describe_plan(plan), ensure_ascii=False, indent=2))
            return 0
        validate_execution_guards(plan, confirm_target=args.confirm_target, environment=os.environ)
        execute_restore(plan)
        print(
            json.dumps(
                {
                    "status": "verified",
                    "archive": str(plan.archive),
                    "targetDatabase": plan.target_database,
                },
                ensure_ascii=False,
                indent=2,
            )
        )
        return 0
    except (OperationSafetyError, OSError, RuntimeError) as error:
        print(f"ERROR: {error}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
