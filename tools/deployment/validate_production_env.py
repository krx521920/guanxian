#!/usr/bin/env python3
"""Fail-closed validation for production deployment environment variables."""

from __future__ import annotations

import argparse
import os
import sys
from decimal import Decimal, InvalidOperation
from pathlib import Path
from urllib.parse import urlsplit


REQUIRED = (
    "POSTGRES_PASSWORD",
    "MINIO_ROOT_USER",
    "MINIO_ROOT_PASSWORD",
    "SPRING_PROFILES_ACTIVE",
    "GUANXIAN_SECURITY_MODE",
    "GUANXIAN_MEMBER_REPOSITORY",
    "GUANXIAN_BUSINESS_REPOSITORY",
    "GUANXIAN_SEED_DEMO_DATA",
    "GUANXIAN_JWT_ISSUER_URI",
    "GUANXIAN_JWT_JWK_SET_URI",
    "GUANXIAN_JWT_PRINCIPAL_CLAIM",
    "GUANXIAN_STORAGE_BACKEND",
    "GUANXIAN_STORAGE_BUCKET",
    "GUANXIAN_STORAGE_ENDPOINT",
    "GUANXIAN_STORAGE_ACCESS_KEY",
    "GUANXIAN_STORAGE_SECRET_KEY",
    "GUANXIAN_STORAGE_REDIS_URL",
    "GUANXIAN_STORAGE_MAX_SIZE_BYTES",
    "GUANXIAN_STORAGE_RATE_LIMIT_ENABLED",
    "GUANXIAN_STORAGE_RATE_LIMIT_PER_MINUTE",
    "SPRING_DATA_REDIS_HOST",
    "SPRING_DATA_REDIS_PORT",
    "GUANXIAN_AI_PROVIDER_ENABLED",
    "GUANXIAN_RAG_EXTERNAL_MODEL_DATA_EGRESS_ENABLED",
    "GUANXIAN_RAG_MAX_ESTIMATED_COST",
    "WEB_OIDC_AUTHORITY",
    "WEB_OIDC_CLIENT_ID",
    "WEB_OIDC_REDIRECT_URI",
    "WEB_OIDC_POST_LOGOUT_REDIRECT_URI",
    "WEB_OIDC_SCOPE",
)
URL_KEYS = (
    "GUANXIAN_JWT_ISSUER_URI",
    "GUANXIAN_JWT_JWK_SET_URI",
    "GUANXIAN_STORAGE_ENDPOINT",
    "WEB_OIDC_AUTHORITY",
    "WEB_OIDC_REDIRECT_URI",
    "WEB_OIDC_POST_LOGOUT_REDIRECT_URI",
)
PLACEHOLDER_HOSTS = {"example.com", "identity.example.com", "localhost", "127.0.0.1", "::1"}
WEAK_SECRET_MARKERS = {"change_me", "changeme", "password", "secret", "replace_me"}
MAX_ATTACHMENT_SIZE_BYTES = 100 * 1024 * 1024


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


def _validate_https_url(key: str, value: str, errors: list[str]) -> None:
    parsed = urlsplit(value)
    host = (parsed.hostname or "").casefold()
    if parsed.scheme.casefold() != "https" or not host:
        errors.append(f"{key}: must be an absolute HTTPS URL")
    if parsed.username or parsed.password or parsed.fragment:
        errors.append(f"{key}: credentials and fragments are forbidden")
    if host in PLACEHOLDER_HOSTS or host.endswith(".example") or host.endswith(".invalid"):
        errors.append(f"{key}: loopback or placeholder host is forbidden")


def _validate_redis_url(key: str, value: str, errors: list[str]) -> None:
    parsed = urlsplit(value)
    host = (parsed.hostname or "").casefold()
    if parsed.scheme.casefold() != "rediss" or not host:
        errors.append(f"{key}: must be an absolute rediss:// URL in production")
    if parsed.fragment:
        errors.append(f"{key}: fragments are forbidden")
    if host in PLACEHOLDER_HOSTS or host.endswith(".example") or host.endswith(".invalid"):
        errors.append(f"{key}: loopback or placeholder host is forbidden")


def _positive_int(
    cleaned: dict[str, str], key: str, errors: list[str], *, maximum: int | None = None
) -> int | None:
    value = cleaned.get(key, "")
    if not value:
        return None
    try:
        parsed = int(value)
    except ValueError:
        errors.append(f"{key}: must be an integer")
        return None
    if parsed <= 0 or maximum is not None and parsed > maximum:
        suffix = f" between 1 and {maximum}" if maximum is not None else " positive"
        errors.append(f"{key}: must be{suffix}")
        return None
    return parsed


def validate(values: dict[str, str]) -> list[str]:
    errors: list[str] = []
    cleaned = {key: str(value).strip() for key, value in values.items()}

    for key in REQUIRED:
        if not cleaned.get(key):
            errors.append(f"{key}: required")

    for key in ("POSTGRES_PASSWORD", "MINIO_ROOT_PASSWORD", "GUANXIAN_STORAGE_SECRET_KEY"):
        value = cleaned.get(key, "")
        if value and len(value) < 16:
            errors.append(f"{key}: must contain at least 16 characters")
        if value and value.casefold() in WEAK_SECRET_MARKERS:
            errors.append(f"{key}: known placeholder or weak value is forbidden")

    storage_access_key = cleaned.get("GUANXIAN_STORAGE_ACCESS_KEY", "")
    if storage_access_key.casefold() in WEAK_SECRET_MARKERS:
        errors.append("GUANXIAN_STORAGE_ACCESS_KEY: placeholder value is forbidden")

    for key in URL_KEYS:
        value = cleaned.get(key, "")
        if value:
            _validate_https_url(key, value, errors)

    redis_url = cleaned.get("GUANXIAN_STORAGE_REDIS_URL", "")
    if redis_url:
        _validate_redis_url("GUANXIAN_STORAGE_REDIS_URL", redis_url, errors)

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
    if cleaned.get("GUANXIAN_BUSINESS_REPOSITORY", "").casefold() != "postgres":
        errors.append("GUANXIAN_BUSINESS_REPOSITORY: must be postgres")
    if cleaned.get("GUANXIAN_SEED_DEMO_DATA", "").casefold() != "false":
        errors.append("GUANXIAN_SEED_DEMO_DATA: must be false")
    if cleaned.get("GUANXIAN_STORAGE_BACKEND", "").casefold() != "minio":
        errors.append("GUANXIAN_STORAGE_BACKEND: must be minio")
    if cleaned.get("GUANXIAN_STORAGE_RATE_LIMIT_ENABLED", "").casefold() != "true":
        errors.append("GUANXIAN_STORAGE_RATE_LIMIT_ENABLED: must be true")
    if "openid" not in cleaned.get("WEB_OIDC_SCOPE", "").split():
        errors.append("WEB_OIDC_SCOPE: must include openid")

    _positive_int(
        cleaned,
        "GUANXIAN_STORAGE_MAX_SIZE_BYTES",
        errors,
        maximum=MAX_ATTACHMENT_SIZE_BYTES,
    )
    _positive_int(
        cleaned,
        "GUANXIAN_STORAGE_RATE_LIMIT_PER_MINUTE",
        errors,
        maximum=10_000,
    )
    _positive_int(cleaned, "SPRING_DATA_REDIS_PORT", errors, maximum=65_535)

    ai_enabled = cleaned.get("GUANXIAN_AI_PROVIDER_ENABLED", "").casefold()
    if ai_enabled not in {"true", "false"}:
        errors.append("GUANXIAN_AI_PROVIDER_ENABLED: must be true or false")
    egress_enabled = cleaned.get(
        "GUANXIAN_RAG_EXTERNAL_MODEL_DATA_EGRESS_ENABLED", ""
    ).casefold()
    if egress_enabled not in {"true", "false"}:
        errors.append(
            "GUANXIAN_RAG_EXTERNAL_MODEL_DATA_EGRESS_ENABLED: must be true or false"
        )
    if egress_enabled == "true" and ai_enabled != "true":
        errors.append(
            "GUANXIAN_RAG_EXTERNAL_MODEL_DATA_EGRESS_ENABLED: requires "
            "GUANXIAN_AI_PROVIDER_ENABLED=true"
        )

    if ai_enabled == "true":
        for key in (
            "GUANXIAN_AI_PROVIDER_ENDPOINT",
            "GUANXIAN_AI_PROVIDER_API_KEY",
            "GUANXIAN_AI_PROVIDER_MODEL",
        ):
            if not cleaned.get(key):
                errors.append(f"{key}: required when GUANXIAN_AI_PROVIDER_ENABLED=true")
        endpoint = cleaned.get("GUANXIAN_AI_PROVIDER_ENDPOINT", "")
        if endpoint:
            _validate_https_url("GUANXIAN_AI_PROVIDER_ENDPOINT", endpoint, errors)
        api_key = cleaned.get("GUANXIAN_AI_PROVIDER_API_KEY", "")
        if api_key and (len(api_key) < 16 or api_key.casefold() in WEAK_SECRET_MARKERS):
            errors.append("GUANXIAN_AI_PROVIDER_API_KEY: weak or placeholder value is forbidden")

    cost_limit = cleaned.get("GUANXIAN_RAG_MAX_ESTIMATED_COST", "")
    if cost_limit:
        try:
            parsed_cost = Decimal(cost_limit)
            if not parsed_cost.is_finite() or parsed_cost <= 0:
                raise InvalidOperation
        except InvalidOperation:
            errors.append("GUANXIAN_RAG_MAX_ESTIMATED_COST: must be a finite positive decimal")

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
