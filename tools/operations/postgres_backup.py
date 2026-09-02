#!/usr/bin/env python3
"""Create an integrity-manifested PostgreSQL custom-format backup."""

from __future__ import annotations

import argparse
import json
import os
import subprocess
import sys
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path
from typing import Sequence

try:
    from tools.operations.postgres_common import (
        OperationSafetyError,
        compose_command,
        repository_root,
        resolve_existing_file,
        sha256_file,
        validate_identifier,
    )
except ModuleNotFoundError:  # Supports direct execution from outside the repository root.
    from postgres_common import (  # type: ignore[no-redef]
        OperationSafetyError,
        compose_command,
        repository_root,
        resolve_existing_file,
        sha256_file,
        validate_identifier,
    )


@dataclass(frozen=True)
class BackupPlan:
    database: str
    archive: Path
    partial_archive: Path
    manifest: Path
    command: tuple[str, ...]


def build_backup_plan(
    *,
    compose_file: Path,
    output_dir: Path,
    database: str,
    user: str,
    timestamp: datetime | None = None,
) -> BackupPlan:
    compose_file = resolve_existing_file(compose_file, "compose file")
    database = validate_identifier(database, "database")
    user = validate_identifier(user, "database user")
    output_dir = output_dir.expanduser().resolve()
    captured_at = timestamp or datetime.now(timezone.utc)
    stamp = captured_at.astimezone(timezone.utc).strftime("%Y%m%dT%H%M%SZ")
    archive = output_dir / f"{database}-{stamp}.dump"
    return BackupPlan(
        database=database,
        archive=archive,
        partial_archive=archive.with_suffix(archive.suffix + ".partial"),
        manifest=archive.with_suffix(archive.suffix + ".manifest.json"),
        command=compose_command(
            compose_file,
            (
                "pg_dump",
                "--format=custom",
                "--compress=9",
                "--no-owner",
                "--no-privileges",
                "--username",
                user,
                "--dbname",
                database,
            ),
        ),
    )


def describe_plan(plan: BackupPlan) -> dict[str, object]:
    return {
        "mode": "dry-run",
        "operation": "postgres-backup",
        "database": plan.database,
        "archive": str(plan.archive),
        "manifest": str(plan.manifest),
        "command": list(plan.command),
    }


def execute_backup(plan: BackupPlan, *, created_at: datetime | None = None) -> dict[str, object]:
    if plan.archive.exists() or plan.partial_archive.exists() or plan.manifest.exists():
        raise OperationSafetyError("backup destination already exists; refusing to overwrite it")

    plan.archive.parent.mkdir(parents=True, exist_ok=True)
    try:
        with plan.partial_archive.open("xb") as destination:
            completed = subprocess.run(
                plan.command,
                stdout=destination,
                stderr=subprocess.PIPE,
                check=False,
            )
            destination.flush()
            os.fsync(destination.fileno())
        if completed.returncode != 0:
            detail = completed.stderr.decode("utf-8", errors="replace")[-1000:].strip()
            raise RuntimeError(f"pg_dump failed with exit code {completed.returncode}: {detail}")
        if plan.partial_archive.stat().st_size == 0:
            raise RuntimeError("pg_dump produced an empty archive")
        plan.partial_archive.replace(plan.archive)

        captured_at = created_at or datetime.now(timezone.utc)
        manifest = {
            "schemaVersion": 1,
            "createdAt": captured_at.astimezone(timezone.utc).isoformat(),
            "database": plan.database,
            "archiveFilename": plan.archive.name,
            "sizeBytes": plan.archive.stat().st_size,
            "sha256": sha256_file(plan.archive),
            "format": "pg_dump-custom",
        }
        temporary_manifest = plan.manifest.with_suffix(plan.manifest.suffix + ".partial")
        with temporary_manifest.open("x", encoding="utf-8", newline="\n") as destination:
            json.dump(manifest, destination, ensure_ascii=False, indent=2, sort_keys=True)
            destination.write("\n")
            destination.flush()
            os.fsync(destination.fileno())
        temporary_manifest.replace(plan.manifest)
        return manifest
    except Exception:
        plan.partial_archive.unlink(missing_ok=True)
        plan.manifest.with_suffix(plan.manifest.suffix + ".partial").unlink(missing_ok=True)
        if not plan.manifest.exists():
            plan.archive.unlink(missing_ok=True)
        raise


def parse_args(argv: Sequence[str] | None = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--compose-file",
        type=Path,
        default=repository_root() / "compose.production.yml",
        help="production Docker Compose file containing the fixed 'postgres' service",
    )
    parser.add_argument(
        "--output-dir",
        type=Path,
        default=repository_root() / "backups" / "postgres",
        help="directory for the archive and integrity manifest",
    )
    parser.add_argument("--database", default=os.getenv("POSTGRES_DB", "guanxian"))
    parser.add_argument("--user", default=os.getenv("POSTGRES_USER", "guanxian"))
    parser.add_argument(
        "--dry-run",
        action="store_true",
        help="validate parameters and print the exact plan without running Docker",
    )
    return parser.parse_args(argv)


def main(argv: Sequence[str] | None = None) -> int:
    args = parse_args(argv)
    try:
        plan = build_backup_plan(
            compose_file=args.compose_file,
            output_dir=args.output_dir,
            database=args.database,
            user=args.user,
        )
        if args.dry_run:
            print(json.dumps(describe_plan(plan), ensure_ascii=False, indent=2))
            return 0
        manifest = execute_backup(plan)
        print(json.dumps({"archive": str(plan.archive), "manifest": manifest}, ensure_ascii=False, indent=2))
        return 0
    except (OperationSafetyError, OSError, RuntimeError) as error:
        print(f"ERROR: {error}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
