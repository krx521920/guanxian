#!/usr/bin/env python3
"""Render a Prometheus file_sd target from a production HTTPS MinIO endpoint."""

from __future__ import annotations

import argparse
import json
import os
import sys
import tempfile
from pathlib import Path
from urllib.parse import urlsplit


class MinioTargetError(ValueError):
    pass


def target_from_endpoint(endpoint: str) -> str:
    parsed = urlsplit(endpoint.strip())
    if parsed.scheme.casefold() != "https" or not parsed.hostname:
        raise MinioTargetError("MinIO metrics endpoint must be an absolute HTTPS URL")
    if parsed.username or parsed.password or parsed.query or parsed.fragment:
        raise MinioTargetError("MinIO endpoint must not contain credentials, query, or fragment")
    if parsed.path not in {"", "/"}:
        raise MinioTargetError("MinIO endpoint must not contain a path")
    host = parsed.hostname
    if ":" in host and not host.startswith("["):
        host = f"[{host}]"
    port = parsed.port or 443
    return f"{host}:{port}"


def render(endpoint: str) -> str:
    payload = [{"targets": [target_from_endpoint(endpoint)], "labels": {"service": "minio"}}]
    return json.dumps(payload, ensure_ascii=False, indent=2, sort_keys=True) + "\n"


def write_atomic(output: Path, content: str) -> Path:
    target = output.expanduser().resolve()
    target.parent.mkdir(parents=True, exist_ok=True)
    descriptor, temporary_name = tempfile.mkstemp(prefix=f".{target.name}.", dir=target.parent)
    temporary = Path(temporary_name)
    try:
        with os.fdopen(descriptor, "w", encoding="utf-8", newline="\n") as destination:
            destination.write(content)
            destination.flush()
            os.fsync(destination.fileno())
        os.chmod(temporary, 0o600)
        temporary.replace(target)
        return target
    except Exception:
        temporary.unlink(missing_ok=True)
        raise


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--endpoint", required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args(argv)
    try:
        write_atomic(args.output, render(args.endpoint))
    except (OSError, MinioTargetError, ValueError) as error:
        print(f"MinIO Prometheus target invalid: {error}", file=sys.stderr)
        return 2
    print(f"MinIO Prometheus target rendered: {args.output}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
