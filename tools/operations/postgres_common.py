"""Shared validation and integrity helpers for PostgreSQL operations."""

from __future__ import annotations

import hashlib
import re
from pathlib import Path
from typing import Iterable


IDENTIFIER_PATTERN = re.compile(r"^[a-z][a-z0-9_]{0,62}$")
RESTORE_TARGET_PREFIX = "guanxian_restore_test_"
EXECUTION_GUARD_NAME = "GUANXIAN_ALLOW_TEST_RESTORE"
EXECUTION_GUARD_VALUE = "I_UNDERSTAND_THIS_DROPS_TEST_DATA"


class OperationSafetyError(ValueError):
    """Raised before an unsafe or malformed operation can reach PostgreSQL."""


def repository_root() -> Path:
    return Path(__file__).resolve().parents[2]


def validate_identifier(value: str, label: str) -> str:
    if not IDENTIFIER_PATTERN.fullmatch(value):
        raise OperationSafetyError(
            f"{label} must start with a lowercase letter and contain only "
            "lowercase letters, digits, or underscores (maximum 63 characters)"
        )
    return value


def validate_restore_target(target: str, production_database: str) -> str:
    validate_identifier(target, "restore target database")
    validate_identifier(production_database, "production database")
    if target == production_database:
        raise OperationSafetyError("restore target must never be the production database")
    if not target.startswith(RESTORE_TARGET_PREFIX) or target == RESTORE_TARGET_PREFIX:
        raise OperationSafetyError(
            f"restore target must start with {RESTORE_TARGET_PREFIX!r} and include a suffix"
        )
    return target


def resolve_existing_file(path: Path, label: str) -> Path:
    resolved = path.expanduser().resolve()
    if not resolved.is_file():
        raise OperationSafetyError(f"{label} does not exist or is not a regular file: {resolved}")
    return resolved


def sha256_file(path: Path, chunk_size: int = 1024 * 1024) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(chunk_size), b""):
            digest.update(chunk)
    return digest.hexdigest()


def compose_command(compose_file: Path, postgres_args: Iterable[str]) -> tuple[str, ...]:
    return (
        "docker",
        "compose",
        "--file",
        str(compose_file),
        "exec",
        "--no-TTY",
        "postgres",
        *postgres_args,
    )
