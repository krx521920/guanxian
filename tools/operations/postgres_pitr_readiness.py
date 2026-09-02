#!/usr/bin/env python3
"""Force and verify PostgreSQL WAL archiving, producing a traceable PITR readiness report."""

from __future__ import annotations

import argparse
import json
import subprocess
import sys
import time
from datetime import datetime, timezone
from pathlib import Path

try:
    from tools.operations.postgres_common import compose_command, repository_root, resolve_existing_file, validate_identifier
except ModuleNotFoundError:
    from postgres_common import compose_command, repository_root, resolve_existing_file, validate_identifier  # type: ignore[no-redef]


def query(compose_file: Path, database: str, user: str, sql: str) -> str:
    command = compose_command(compose_file, (
        "psql", "--username", user, "--dbname", database,
        "--tuples-only", "--no-align", "--command", sql,
    ))
    completed = subprocess.run(command, stdout=subprocess.PIPE, stderr=subprocess.PIPE, check=False)
    if completed.returncode != 0:
        detail = completed.stderr.decode("utf-8", errors="replace")[-1000:].strip()
        raise RuntimeError(f"PostgreSQL PITR readiness query failed: {detail}")
    return completed.stdout.decode("utf-8", errors="replace").strip()


def archiver_state(compose_file: Path, database: str, user: str) -> tuple[int, int, str]:
    raw = query(compose_file, database, user, "SELECT archived_count || '|' || failed_count || '|' || COALESCE(last_archived_wal, '') FROM pg_stat_archiver")
    parts = raw.split("|", 2)
    if len(parts) != 3:
        raise RuntimeError("PostgreSQL returned an unexpected pg_stat_archiver result")
    return int(parts[0]), int(parts[1]), parts[2]


def execute(compose_file: Path, database: str, user: str, timeout_seconds: float) -> dict[str, object]:
    settings = query(
        compose_file, database, user,
        "SELECT current_setting('archive_mode') || '|' || current_setting('wal_level') || '|' || current_setting('archive_command')",
    ).split("|", 2)
    if len(settings) != 3 or settings[0] != "on" or settings[1] not in {"replica", "logical"} or not settings[2].strip():
        raise RuntimeError("PostgreSQL WAL archiving is not configured for PITR")
    before_archived, before_failed, _ = archiver_state(compose_file, database, user)
    switched_wal = query(compose_file, database, user, "SELECT pg_walfile_name(pg_switch_wal())")
    deadline = time.monotonic() + timeout_seconds
    after_archived, after_failed, last_archived_wal = before_archived, before_failed, ""
    while time.monotonic() < deadline:
        after_archived, after_failed, last_archived_wal = archiver_state(compose_file, database, user)
        if after_archived > before_archived and last_archived_wal:
            break
        time.sleep(1)
    if after_archived <= before_archived or not last_archived_wal:
        raise RuntimeError("forced WAL segment was not archived before the readiness timeout")
    if after_failed > before_failed:
        raise RuntimeError("pg_stat_archiver recorded a new archive failure during the drill")
    return {
        "schemaVersion": 1,
        "operation": "postgres-pitr-readiness",
        "status": "verified",
        "completedAt": datetime.now(timezone.utc).isoformat(),
        "database": database,
        "archiveMode": settings[0],
        "walLevel": settings[1],
        "switchedWal": switched_wal,
        "lastArchivedWal": last_archived_wal,
        "archivedCountBefore": before_archived,
        "archivedCountAfter": after_archived,
        "failedCount": after_failed,
    }


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--compose-file", type=Path, default=repository_root() / "compose.production.yml")
    parser.add_argument("--database", default="guanxian")
    parser.add_argument("--user", default="guanxian")
    parser.add_argument("--timeout-seconds", type=float, default=30)
    parser.add_argument("--report", type=Path, required=True)
    parser.add_argument("--execute", action="store_true")
    args = parser.parse_args(argv)
    try:
        compose_file = resolve_existing_file(args.compose_file, "compose file")
        database = validate_identifier(args.database, "database")
        user = validate_identifier(args.user, "database user")
        if args.timeout_seconds <= 0:
            raise ValueError("timeout must be positive")
        if not args.execute:
            print(json.dumps({"mode": "dry-run", "operation": "postgres-pitr-readiness", "composeFile": str(compose_file), "database": database}, ensure_ascii=False, indent=2))
            return 0
        report = execute(compose_file, database, user, args.timeout_seconds)
        target = args.report.expanduser().resolve()
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(json.dumps(report, ensure_ascii=False, indent=2, sort_keys=True) + "\n", encoding="utf-8")
        print(json.dumps(report, ensure_ascii=False, indent=2))
        return 0
    except (OSError, ValueError, RuntimeError) as error:
        print(f"ERROR: {error}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
