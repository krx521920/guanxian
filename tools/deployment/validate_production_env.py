#!/usr/bin/env python3
"""Fail-closed validation for production deployment environment variables."""

from __future__ import annotations

import argparse
import os
import sys
from pathlib import Path
from urllib.parse import urlsplit


REQUIRED = (
    "POSTGRES_PASSWORD",
    "MINIO_ROOT_USER",
    "MINIO_ROOT_PASSWORD",
    "SPRING_PROFILES_ACTIVE",
    "GUANXIAN_SECURITY_MODE",
    "GUANXIAN_MEMBER_REPOSITORY",
    "GUANXIAN_SEED_DEMO_DATA",
    "GUANXIAN_JWT_ISSUER_URI",
    "GUANXIAN_JWT_JWK_SET_URI",
    "GUANXIAN_JWT_PRINCIPAL_CLAIM",
    "WEB_OIDC_AUTHORITY",
    "WEB_OIDC_CLIENT_ID",
    "WEB_OIDC_REDIRECT_URI",
    "WEB_OIDC_POST_LOGOUT_REDIRECT_URI",
    "WEB_OIDC_SCOPE",
)
URL_KEYS = (
    "GUANXIAN_JWT_ISSUER_URI",
    "GUANXIAN_JWT_JWK_SET_URI",
    "WEB_OIDC_AUTHORITY",
    "WEB_OIDC_REDIRECT_URI",
    "WEB_OIDC_POST_LOGOUT_REDIRECT_URI",
)
PLACEHOLDER_HOSTS = {"example.com", "identity.example.com", "localhost", "127.0.0.1", "::1"}
WEAK_SECRET_MARKERS = {"change_me", "changeme", "password", "secret", "replace_me"}


def load_env_file(path: Path) -> dict[str, str]:
    values: dict[str, str] = {}
    for number, raw_line in enumerate(path.read_text(encoding="utf-8-sig").splitlines(), start=1):
        line = raw_line.strip()
        if not line or line.startswith("#"):
            continue
        if line.startswith("export "):
            line = line.removeprefix("export ").lstrip()
        if "=" not in line:
            raise ValueError(f"{path}:{number}: expected KEY=VALUE")
        key, value = line.split("=", 1)
        key = key.strip()
        value = value.strip()
        if not key or key in values:
            raise ValueError(f"{path}:{number}: empty or duplicate key")
        if len(value) >= 2 and value[0] == value[-1] and value[0] in {"'", '"'}:
            value = value[1:-1]
        values[key] = value
    return values


def _normalized_url(value: str) -> str:
    return value.rstrip("/")


def validate(values: dict[str, str]) -> list[str]:
    errors: list[str] = []
    cleaned = {key: str(value).strip() for key, value in values.items()}

    for key in REQUIRED:
        if not cleaned.get(key):
            errors.append(f"{key}: required")

    for key in ("POSTGRES_PASSWORD", "MINIO_ROOT_PASSWORD"):
        value = cleaned.get(key, "")
        if value and len(value) < 16:
            errors.append(f"{key}: must contain at least 16 characters")
        if value and value.casefold() in WEAK_SECRET_MARKERS:
            errors.append(f"{key}: known placeholder or weak value is forbidden")

    for key in URL_KEYS:
        value = cleaned.get(key, "")
        if not value:
            continue
        parsed = urlsplit(value)
        host = (parsed.hostname or "").casefold()
        if parsed.scheme.casefold() != "https" or not host:
            errors.append(f"{key}: must be an absolute HTTPS URL")
        if parsed.username or parsed.password or parsed.fragment:
            errors.append(f"{key}: credentials and fragments are forbidden")
        if host in PLACEHOLDER_HOSTS or host.endswith(".example") or host.endswith(".invalid"):
            errors.append(f"{key}: loopback or placeholder host is forbidden")

    issuer = cleaned.get("GUANXIAN_JWT_ISSUER_URI", "")
    authority = cleaned.get("WEB_OIDC_AUTHORITY", "")
    if issuer and authority and _normalized_url(issuer) != _normalized_url(authority):
        errors.append("WEB_OIDC_AUTHORITY: must exactly match GUANXIAN_JWT_ISSUER_URI")

    profiles = {item.strip().casefold() for item in cleaned.get("SPRING_PROFILES_ACTIVE", "").split(",")}
    if profiles.isdisjoint({"prod", "production"}):
        errors.append("SPRING_PROFILES_ACTIVE: prod or production profile is required")
    if cleaned.get("GUANXIAN_SECURITY_MODE", "").casefold() != "jwt":
        errors.append("GUANXIAN_SECURITY_MODE: must be jwt")
    if cleaned.get("GUANXIAN_MEMBER_REPOSITORY", "").casefold() != "postgres":
        errors.append("GUANXIAN_MEMBER_REPOSITORY: must be postgres")
    if cleaned.get("GUANXIAN_SEED_DEMO_DATA", "").casefold() != "false":
        errors.append("GUANXIAN_SEED_DEMO_DATA: must be false")
    if "openid" not in cleaned.get("WEB_OIDC_SCOPE", "").split():
        errors.append("WEB_OIDC_SCOPE: must include openid")

    client_id = cleaned.get("WEB_OIDC_CLIENT_ID", "")
    if client_id.casefold() in {"client", "client_id", "change_me", "replace_me"}:
        errors.append("WEB_OIDC_CLIENT_ID: placeholder value is forbidden")
    if any(character.isspace() for character in client_id):
        errors.append("WEB_OIDC_CLIENT_ID: whitespace is forbidden")

    return errors


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--env-file", type=Path, help="dotenv file to validate; defaults to process environment")
    args = parser.parse_args(argv)

    try:
        values = load_env_file(args.env_file) if args.env_file else dict(os.environ)
    except (OSError, UnicodeError, ValueError) as exception:
        print(f"production configuration invalid: {exception}", file=sys.stderr)
        return 2

    errors = validate(values)
    if errors:
        print("production configuration invalid:", file=sys.stderr)
        for error in errors:
            print(f"- {error}", file=sys.stderr)
        return 1
    print("production configuration contract is valid")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
