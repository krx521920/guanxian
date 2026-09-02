#!/usr/bin/env python3
"""Mirror one MinIO bucket to an integrity-manifested filesystem snapshot."""

from __future__ import annotations

import argparse
import json
import os
import shutil
import subprocess
import sys
from datetime import datetime, timezone
from pathlib import Path

try:
    from tools.operations.minio_common import (
        MinioOperationError, mc_environment, read_secret, snapshot_files,
        validate_bucket, validate_endpoint,
    )
    from tools.operations.postgres_common import repository_root
except ModuleNotFoundError:
    from minio_common import (  # type: ignore[no-redef]
        MinioOperationError, mc_environment, read_secret, snapshot_files,
        validate_bucket, validate_endpoint,
    )
    from postgres_common import repository_root  # type: ignore[no-redef]


def execute(args: argparse.Namespace) -> dict[str, object]:
    endpoint = validate_endpoint(args.endpoint)
    bucket = validate_bucket(args.bucket)
    stamp = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ")
    snapshot = args.output_dir.expanduser().resolve() / f"{bucket}-{stamp}"
    partial = snapshot.with_name(snapshot.name + ".partial")
    if snapshot.exists() or partial.exists():
        raise MinioOperationError("snapshot destination already exists")
    access_key = read_secret(args.access_key_file, "access key")
    secret_key = read_secret(args.secret_key_file, "secret key")
    try:
        data_dir = partial / "data"
        data_dir.mkdir(parents=True)
        environment = dict(os.environ)
        environment.update(mc_environment(endpoint, access_key, secret_key))
        command = [args.mc, "mirror", "--overwrite", "--json", f"guanxian/{bucket}", str(data_dir)]
        completed = subprocess.run(command, env=environment, stdout=subprocess.DEVNULL, stderr=subprocess.PIPE, check=False)
        if completed.returncode != 0:
            detail = completed.stderr.decode("utf-8", errors="replace")[-1000:].strip()
            raise RuntimeError(f"mc mirror failed with exit code {completed.returncode}: {detail}")
        captured_at = datetime.now(timezone.utc)
        manifest = {
            "schemaVersion": 1,
            "operation": "minio-backup",
            "createdAt": captured_at.isoformat(),
            "bucket": bucket,
            "files": snapshot_files(data_dir),
        }
        (partial / "manifest.json").write_text(
            json.dumps(manifest, ensure_ascii=False, indent=2, sort_keys=True) + "\n", encoding="utf-8"
        )
        partial.replace(snapshot)
        return {"status": "created", "snapshot": str(snapshot), "objectCount": len(manifest["files"])}
    except Exception:
        shutil.rmtree(partial, ignore_errors=True)
        raise


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--endpoint", required=True)
    parser.add_argument("--bucket", required=True)
    parser.add_argument("--access-key-file", type=Path, required=True)
    parser.add_argument("--secret-key-file", type=Path, required=True)
    parser.add_argument("--output-dir", type=Path, default=repository_root() / "backups" / "minio")
    parser.add_argument("--mc", default="mc")
    parser.add_argument("--execute", action="store_true")
    args = parser.parse_args(argv)
    try:
        validate_endpoint(args.endpoint)
        validate_bucket(args.bucket)
        if not args.execute:
            print(json.dumps({"mode": "dry-run", "operation": "minio-backup", "bucket": args.bucket, "outputDir": str(args.output_dir.expanduser().resolve())}, ensure_ascii=False, indent=2))
            return 0
        print(json.dumps(execute(args), ensure_ascii=False, indent=2))
        return 0
    except (MinioOperationError, OSError, RuntimeError) as error:
        print(f"ERROR: {error}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
