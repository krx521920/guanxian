#!/usr/bin/env python3
"""Restore a MinIO snapshot into a guarded isolated test bucket and verify it."""

from __future__ import annotations

import argparse
import json
import os
import subprocess
import sys
import time
from datetime import datetime, timezone
from pathlib import Path

try:
    from tools.operations.minio_common import (
        EXECUTION_GUARD_NAME, EXECUTION_GUARD_VALUE, MinioOperationError,
        mc_environment, read_secret, validate_bucket, validate_endpoint, verify_snapshot,
    )
except ModuleNotFoundError:
    from minio_common import (  # type: ignore[no-redef]
        EXECUTION_GUARD_NAME, EXECUTION_GUARD_VALUE, MinioOperationError,
        mc_environment, read_secret, validate_bucket, validate_endpoint, verify_snapshot,
    )


def run(command: list[str], environment: dict[str, str], *, capture: bool = False) -> subprocess.CompletedProcess[bytes]:
    completed = subprocess.run(
        command, env=environment,
        stdout=subprocess.PIPE if capture else subprocess.DEVNULL,
        stderr=subprocess.PIPE, check=False,
    )
    if completed.returncode != 0:
        detail = completed.stderr.decode("utf-8", errors="replace")[-1000:].strip()
        raise RuntimeError(f"mc command failed with exit code {completed.returncode}: {detail}")
    return completed


def execute(args: argparse.Namespace) -> dict[str, object]:
    endpoint = validate_endpoint(args.endpoint)
    target = validate_bucket(args.target_bucket, restore=True)
    if args.confirm_target != target:
        raise MinioOperationError("--confirm-target must exactly match the isolated test bucket")
    if os.environ.get(EXECUTION_GUARD_NAME) != EXECUTION_GUARD_VALUE:
        raise MinioOperationError(f"set {EXECUTION_GUARD_NAME}={EXECUTION_GUARD_VALUE} before execution")
    data_dir, manifest = verify_snapshot(args.snapshot)
    environment = dict(os.environ)
    environment.update(mc_environment(
        endpoint,
        read_secret(args.access_key_file, "access key"),
        read_secret(args.secret_key_file, "secret key"),
    ))
    started_at = datetime.now(timezone.utc)
    started = time.monotonic()
    run([args.mc, "mb", "--ignore-existing", f"guanxian/{target}"], environment)
    run([args.mc, "mirror", "--overwrite", str(data_dir), f"guanxian/{target}"], environment)
    difference = run([args.mc, "diff", "--json", str(data_dir), f"guanxian/{target}"], environment, capture=True)
    if difference.stdout.strip():
        raise RuntimeError("MinIO restore verification failed: mc diff reported differences")
    return {
        "schemaVersion": 1,
        "operation": "minio-restore-drill",
        "status": "verified",
        "startedAt": started_at.isoformat(),
        "completedAt": datetime.now(timezone.utc).isoformat(),
        "durationSeconds": round(time.monotonic() - started, 3),
        "sourceBucket": manifest.get("bucket"),
        "targetBucket": target,
        "objectCount": len(manifest.get("files", [])),
        "verification": {"inventoryAndSha256Verified": True, "mcDiffEmpty": True, "targetIsIsolated": True},
    }


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--snapshot", type=Path, required=True)
    parser.add_argument("--endpoint", required=True)
    parser.add_argument("--target-bucket", required=True)
    parser.add_argument("--confirm-target")
    parser.add_argument("--access-key-file", type=Path, required=True)
    parser.add_argument("--secret-key-file", type=Path, required=True)
    parser.add_argument("--mc", default="mc")
    parser.add_argument("--report", type=Path)
    parser.add_argument("--execute", action="store_true")
    args = parser.parse_args(argv)
    try:
        validate_bucket(args.target_bucket, restore=True)
        data_dir, manifest = verify_snapshot(args.snapshot)
        if not args.execute:
            print(json.dumps({"mode": "dry-run", "operation": "minio-restore-drill", "sourceBucket": manifest.get("bucket"), "targetBucket": args.target_bucket, "objectCount": len(manifest.get("files", []))}, ensure_ascii=False, indent=2))
            return 0
        report = execute(args)
        if args.report:
            target = args.report.expanduser().resolve()
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_text(json.dumps(report, ensure_ascii=False, indent=2, sort_keys=True) + "\n", encoding="utf-8")
            report["report"] = str(target)
        print(json.dumps(report, ensure_ascii=False, indent=2))
        return 0
    except (MinioOperationError, OSError, RuntimeError) as error:
        print(f"ERROR: {error}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
