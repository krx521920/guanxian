#!/usr/bin/env python3
"""Prepare a NEW single-host deployment; never start services or overwrite secrets.

Run on Linux as root, outside Git. Passwords are prompted without echo. Only the
initial operator identity is imported, with a password change at first login.
"""
from __future__ import annotations

import argparse
import getpass
import json
import os
import re
import secrets
import subprocess
import sys
import uuid
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
IMAGE_SOURCES = {
    "POSTGRES_IMAGE": "postgres:16-alpine",
    "REDIS_IMAGE": "redis:7-alpine",
    "MINIO_IMAGE": "minio/minio:RELEASE.2025-04-22T22-12-26Z",
    "MINIO_MC_IMAGE": "minio/mc:latest",
    "KEYCLOAK_IMAGE": "quay.io/keycloak/keycloak:26.7.1",
    "CLAMAV_IMAGE": "clamav/clamav:stable_base",
    "NGINX_IMAGE": "nginx:stable-alpine",
}
ASSOCIATION_ID = "00000000-0000-0000-0000-000000000106"


def validate_domain(value: str) -> str:
    value = value.lower()
    if len(value) > 253 or "." not in value or not re.fullmatch(
        r"[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?(?:\.[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?)+", value
    ) or value.rsplit(".", 1)[1].isdigit():
        raise ValueError("domain must be a DNS hostname, without scheme, port or path")
    return value


def validate_username(value: str) -> str:
    if not re.fullmatch(r"[a-z][a-z0-9._-]{2,63}", value):
        raise ValueError("username must start with a letter; use 3-64 lowercase letters/digits/._-")
    if value.startswith("ci-") or value in {"admin", "identity-bootstrap", "test", "demo"}:
        raise ValueError("choose a personal operator username, not a shared/demo account")
    return value


def validate_password(value: str) -> None:
    if not 16 <= len(value) <= 128 or any(ord(c) < 32 for c in value):
        raise ValueError("operator password must contain 16-128 characters without control characters")
    if len(set(value)) < 8:
        raise ValueError("operator password is too repetitive")


def realm(domain: str, username: str, password: str, subject: str) -> dict:
    validate_domain(domain)
    validate_username(username)
    validate_password(password)
    uuid.UUID(subject)
    return {
        "realm": "guanxian", "enabled": True, "sslRequired": "all",
        "registrationAllowed": False, "resetPasswordAllowed": False,
        "bruteForceProtected": True, "failureFactor": 5,
        "passwordPolicy": "length(16) and notUsername(undefined)",
        "roles": {"realm": [{"name": name} for name in (
            "SYSTEM_ADMIN", "ASSOCIATION_ADMIN", "ASSOCIATION_OPERATOR",
            "ENTERPRISE_ADMIN", "ENTERPRISE_MEMBER", "OBSERVER")]},
        "clients": [{
            "clientId": "guanxian-web", "enabled": True, "publicClient": True,
            "standardFlowEnabled": True, "directAccessGrantsEnabled": False,
            "implicitFlowEnabled": False, "serviceAccountsEnabled": False,
            "redirectUris": [f"https://{domain}/auth/callback"],
            "webOrigins": [f"https://{domain}"],
            "attributes": {"pkce.code.challenge.method": "S256",
                           "post.logout.redirect.uris": f"https://{domain}/login"},
        }],
        "users": [{
            "id": subject, "username": username, "enabled": True,
            "firstName": username, "lastName": "Operator",
            "realmRoles": ["SYSTEM_ADMIN"], "requiredActions": ["UPDATE_PASSWORD"],
            "credentials": [{"type": "password", "value": password, "temporary": True}],
        }],
    }


def bootstrap_sql(username: str, subject: str) -> str:
    # Whitelisting and UUID parsing prevent any operator value becoming SQL.
    validate_username(username)
    uuid.UUID(subject)
    return f"""-- Initial binding only. No demo enterprises and no blanket JWT allowlist.
BEGIN;
LOCK TABLE user_account IN EXCLUSIVE MODE;
DO $bootstrap$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM flyway_schema_history WHERE version='23' AND success) THEN
    RAISE EXCEPTION 'V23 must be applied before bootstrap';
  END IF;
  IF EXISTS (SELECT 1 FROM user_account) OR EXISTS (SELECT 1 FROM revoked_identity_subject) THEN
    RAISE EXCEPTION 'Refusing bootstrap: identity data already exists';
  END IF;
  IF NOT EXISTS (SELECT 1 FROM association WHERE id='{ASSOCIATION_ID}'
      AND name='北京地下管线协会' AND status='ACTIVE') THEN
    RAISE EXCEPTION 'Expected association missing; do not guess association ownership';
  END IF;
END $bootstrap$;
INSERT INTO user_account(id, association_id, external_subject, username, display_name, status)
VALUES ('{subject}', '{ASSOCIATION_ID}', '{subject}', '{username}', '{username}', 'ACTIVE');
INSERT INTO audit_log(actor_user_id, actor_subject, actor_username, association_id,
    action, resource_type, resource_id, resource_version, outcome, request_id, details)
VALUES ('{subject}', 'deployment-bootstrap', '{username}', '{ASSOCIATION_ID}',
    'INITIAL_OPERATOR_BINDING', 'USER_ACCOUNT', '{subject}', 0, 'SUCCESS',
    'bootstrap-{subject}', '{{"source":"single-host-first-install","role":"SYSTEM_ADMIN"}}'::jsonb);
COMMIT;
"""


def write_new(path: Path, text: str, mode: int = 0o444) -> None:
    # The parent is 0700/root. 0444 leaf files are necessary for file-backed
    # Docker secrets: Compose does NOT remap uid/gid/mode for bind-mounted files.
    with path.open("x", encoding="utf-8", newline="\n") as output:
        output.write(text)
    path.chmod(mode)


def generate(output: Path, domain: str, username: str, password: str, release: str,
             images: dict[str, str], *, letsencrypt: str = "/etc/letsencrypt") -> None:
    domain = validate_domain(domain)
    validate_username(username)
    validate_password(password)
    if not re.fullmatch(r"[a-z0-9][a-z0-9._-]{0,63}", release):
        raise ValueError("release ID must be a safe lowercase image tag")
    if set(images) != set(IMAGE_SOURCES) or any(
        not re.fullmatch(r"[a-z0-9./_-]+@sha256:[a-f0-9]{64}", ref) for ref in images.values()
    ):
        raise ValueError("every dependency must be pinned to an image digest")
    if output.exists() or output.is_symlink():
        raise ValueError("output already exists; refusing to overwrite credentials or reset identities")
    output.mkdir(mode=0o700)  # Parent must already exist; never create arbitrary parents.
    output.chmod(0o700)
    secure = output / "secrets"
    secure.mkdir(mode=0o700)
    subject = str(uuid.uuid4())
    values = {name: secrets.token_hex(24) for name in (
        "postgres_admin_password", "postgres_password", "keycloak_db_password", "redis_password",
        "minio_root_password", "storage_secret_key", "keycloak_admin_password")}
    values.update({"minio_root_user": "root-" + secrets.token_hex(8),
                   "storage_access_key": "app-" + secrets.token_hex(8)})
    for name, value in values.items():
        write_new(secure / name, value)
    write_new(secure / "storage_redis_url", f"rediss://:{values['redis_password']}@{domain}:6380")
    write_new(secure / "redis.conf", "\n".join((
        "bind 0.0.0.0", "protected-mode yes", "port 6379",
        f"requirepass {values['redis_password']}", "maxmemory 96mb",
        "maxmemory-policy noeviction", 'save ""', "appendonly no", "")))
    write_new(secure / "guanxian-realm.json", json.dumps(
        realm(domain, username, password, subject), ensure_ascii=False, indent=2) + "\n")
    write_new(output / "bootstrap.sql", bootstrap_sql(username, subject), 0o600)
    template = (ROOT / "infrastructure/single-host/nginx.conf.template").read_text(encoding="utf-8")
    write_new(output / "nginx.conf", template.replace("@DOMAIN@", domain))
    for relative in ("acme-webroot", "acme-webroot/.well-known", "acme-webroot/.well-known/acme-challenge"):
        public_challenge_dir = output / relative
        public_challenge_dir.mkdir(mode=0o755)
        public_challenge_dir.chmod(0o755)
    env = {"SITE_DOMAIN": domain, "SINGLE_HOST_DIR": output.resolve().as_posix(),
           "LETSENCRYPT_DIR": letsencrypt, "RELEASE_ID": release, **images}
    write_new(output / "deploy.env", "".join(f"{k}={v}\n" for k, v in env.items()), 0o600)
    write_new(output / "manifest.json", json.dumps({
        "domain": domain, "operator": username, "subject": subject,
        "associationId": ASSOCIATION_ID, "release": release, "images": images,
        "status": "PREPARED_NOT_DEPLOYED",
    }, indent=2) + "\n", 0o600)


def lock_images() -> dict[str, str]:
    locked = {}
    for key, image in IMAGE_SOURCES.items():
        print(f"Pulling official dependency: {image}", flush=True)
        subprocess.run(["docker", "pull", image], check=True)
        result = subprocess.run(["docker", "image", "inspect", image, "--format",
                                 "{{index .RepoDigests 0}}"], check=True,
                                text=True, capture_output=True)
        locked[key] = result.stdout.strip()
    return locked


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--domain", required=True)
    parser.add_argument("--username", required=True)
    parser.add_argument("--release", required=True)
    parser.add_argument("--output", type=Path, default=Path("/opt/guanxian-single"))
    args = parser.parse_args(argv)
    try:
        if os.name != "posix" or os.geteuid() != 0:
            raise ValueError("run on the Linux server with sudo; do not generate production secrets on Windows")
        if not sys.stdin.isatty():
            raise ValueError("interactive terminal required for password entry")
        args.domain = validate_domain(args.domain)
        validate_username(args.username)
        # Restrict operator-managed roots and reject symlink/path substitution.
        output = args.output.absolute()
        if not re.fullmatch(r"/opt/guanxian-[a-z0-9-]+", str(output)) or output.resolve() != output:
            raise ValueError("output must be a new /opt/guanxian-<name> directory without symlinks")
        if output.exists():
            raise ValueError("deployment directory already exists; do not rerun initialization")
        cert = Path("/etc/letsencrypt/live") / args.domain / "fullchain.pem"
        subprocess.run(["openssl", "x509", "-in", str(cert), "-noout", "-checkend", "604800"], check=True)
        subprocess.run(["openssl", "verify", "-verify_hostname", args.domain,
                        "-untrusted", str(cert), str(cert)], check=True)
        password = getpass.getpass("Initial operator password (16+ characters; hidden): ")
        validate_password(password)
        if password != getpass.getpass("Repeat password: "):
            raise ValueError("passwords do not match")
        images = lock_images()
        generate(output, args.domain, args.username, password, args.release, images)
        print(f"Prepared {output}. No services started. No business data imported.")
        print("Keep secrets on this server. Do not upload the deployment directory or private keys.")
        return 0
    except (ValueError, OSError, subprocess.CalledProcessError) as exception:
        print(f"Preparation stopped: {exception}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
