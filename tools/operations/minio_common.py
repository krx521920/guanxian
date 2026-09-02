"""Shared validation and integrity helpers for MinIO backup operations."""

from __future__ import annotations

import hashlib
import json
import re
from pathlib import Path
from urllib.parse import quote, urlsplit


BUCKET_PATTERN = re.compile(r"^[a-z0-9][a-z0-9.-]{1,61}[a-z0-9]$")
RESTORE_BUCKET_PREFIX = "guanxian-restore-test-"
EXECUTION_GUARD_NAME = "GUANXIAN_ALLOW_MINIO_TEST_RESTORE"
EXECUTION_GUARD_VALUE = "I_UNDERSTAND_THIS_WRITES_TEST_OBJECTS"


class MinioOperationError(ValueError):
    pass


def validate_endpoint(value: str) -> str:
    parsed = urlsplit(value.strip())
    if parsed.scheme.casefold() != "https" or not parsed.hostname:
        raise MinioOperationError("MinIO endpoint must be an absolute HTTPS URL")
    if parsed.username or parsed.password or parsed.query or parsed.fragment or parsed.path not in {"", "/"}:
        raise MinioOperationError("MinIO endpoint must not contain credentials, path, query, or fragment")
    return value.strip().rstrip("/")


def validate_bucket(value: str, *, restore: bool = False) -> str:
    if not BUCKET_PATTERN.fullmatch(value) or ".." in value:
        raise MinioOperationError("bucket name is invalid")
    if restore and (not value.startswith(RESTORE_BUCKET_PREFIX) or value == RESTORE_BUCKET_PREFIX):
        raise MinioOperationError(f"restore bucket must use {RESTORE_BUCKET_PREFIX!r} with a suffix")
    return value


def read_secret(path: Path, label: str) -> str:
    resolved = path.expanduser().resolve()
    if not resolved.is_file():
        raise MinioOperationError(f"{label} file does not exist: {resolved}")
    value = resolved.read_text(encoding="utf-8").strip()
    if not value or any(character.isspace() for character in value):
        raise MinioOperationError(f"{label} must be non-empty and contain no whitespace")
    return value


def mc_environment(endpoint: str, access_key: str, secret_key: str) -> dict[str, str]:
    return {
        "MC_HOST_guanxian": (
            f"https://{quote(access_key, safe='')}:{quote(secret_key, safe='')}@"
            f"{urlsplit(endpoint).netloc}"
        )
    }


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def snapshot_files(data_dir: Path) -> list[dict[str, object]]:
    symlinks = [path for path in data_dir.rglob("*") if path.is_symlink()]
    if symlinks:
        raise MinioOperationError("snapshot data must not contain symbolic links")
    return [
        {"path": path.relative_to(data_dir).as_posix(), "sizeBytes": path.stat().st_size, "sha256": sha256_file(path)}
        for path in sorted(data_dir.rglob("*"))
        if path.is_file()
    ]


def verify_snapshot(snapshot: Path) -> tuple[Path, dict[str, object]]:
    root = snapshot.expanduser().resolve()
    manifest_path = root / "manifest.json"
    data_dir = root / "data"
    if not manifest_path.is_file() or not data_dir.is_dir():
        raise MinioOperationError("snapshot must contain manifest.json and data/")
    try:
        manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    except (UnicodeError, json.JSONDecodeError) as error:
        raise MinioOperationError(f"snapshot manifest is invalid: {error}") from error
    if not isinstance(manifest, dict) or manifest.get("schemaVersion") != 1:
        raise MinioOperationError("snapshot manifest schemaVersion must be 1")
    expected = manifest.get("files")
    if not isinstance(expected, list) or expected != snapshot_files(data_dir):
        raise MinioOperationError("snapshot file inventory or SHA-256 verification failed")
    return data_dir, manifest
