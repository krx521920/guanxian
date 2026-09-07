#!/usr/bin/env python3
"""One-time, administrator-run opt-in for personal models on the existing host.

Dry-run by default. Never accepts API keys, rotates a master key, edits sudoers,
changes database records, or restarts services. Reuses the fixed maintenance
commands, adding only a root-owned personal-model Compose overlay to every run.
"""
from __future__ import annotations

import argparse
import base64
import hashlib
import json
import os
from pathlib import Path
import secrets
import stat
import subprocess
import tempfile

DEPLOY = Path('/opt/guanxian-single')
REPO = Path('/home/admin/guanxian')
HELPER = Path('/usr/local/sbin/guanxian-maintain')
KEY = DEPLOY / 'secrets/ai_personal_encryption_key'
OVERLAY = DEPLOY / 'personal-model.compose.yml'
ORIGINAL_HELPER_SHA256 = '2390806386ee5501538cf7f591f5287046f298b04345a8978a9acadaa08e0e00'
MARKER = b"SERVICES = ('server', 'web', 'gateway')"
ADDITION = b"COMPOSE += ['--file', '/opt/guanxian-single/personal-model.compose.yml']\n"
CONFIRMATION = 'I_APPROVE_PERSONAL_MODEL_DATA_EGRESS'
OVERLAY_TEXT = """# Administrator-approved personal model access; maintained outside Git.
services:
  server:
    environment:
      GUANXIAN_RAG_EXTERNAL_MODEL_DATA_EGRESS_ENABLED: 'true'
    secrets:
      - {source: personal_model_encryption_key, target: guanxian.ai.personal.encryption-key}
    networks:
      - personal-model-egress
networks:
  personal-model-egress: {}
secrets:
  personal_model_encryption_key:
    file: /opt/guanxian-single/secrets/ai_personal_encryption_key
"""


def upgrade_helper(original: bytes) -> bytes:
    """Allow only the exact installed policy or our exact additive upgrade."""
    candidate = original.replace(ADDITION, b'', 1) if ADDITION in original else original
    if hashlib.sha256(candidate).hexdigest() != ORIGINAL_HELPER_SHA256:
        raise ValueError('Unknown maintenance helper version; review it instead of overwriting it.')
    if candidate.count(MARKER) != 1:
        raise ValueError('Unexpected maintenance helper structure.')
    return candidate.replace(MARKER, ADDITION + MARKER, 1)


def validate_key(value: bytes) -> None:
    try:
        decoded = base64.b64decode(value.strip(), validate=True)
        if len(decoded) != 32:
            raise ValueError()
    except (ValueError, base64.binascii.Error):
        raise ValueError('Existing master key is invalid; it will not be replaced.') from None


def root_path(path: Path, *, directory: bool = False, private: bool = False) -> None:
    if path.resolve(strict=True) != path:
        raise ValueError('Unexpected symlink in deployment path.')
    info = path.stat()
    expected_type = stat.S_ISDIR if directory else stat.S_ISREG
    if not expected_type(info.st_mode) or info.st_uid != 0 or info.st_mode & 0o022:
        raise ValueError('Deployment path must be root-owned and not group/world writable.')
    if private and info.st_mode & 0o077:
        raise ValueError('Secret parent directory or configuration must be private to root.')


def new_file(path: Path, data: bytes, mode: int) -> None:
    # Parent directories were validated and are root-only. Never truncate an existing key.
    fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, mode)
    with os.fdopen(fd, 'wb') as output:
        output.write(data)
        output.flush()
        os.fsync(output.fileno())
    path.chmod(mode)


def replace_helper(data: bytes) -> None:
    fd, temporary = tempfile.mkstemp(prefix='.guanxian-maintain-', dir=HELPER.parent)
    temporary_path = Path(temporary)
    try:
        with os.fdopen(fd, 'wb') as output:
            output.write(data)
            output.flush()
            os.fsync(output.fileno())
        temporary_path.chmod(0o755)
        os.replace(temporary_path, HELPER)
    finally:
        # Only our exact mkstemp leaf, never a recursive delete.
        temporary_path.unlink(missing_ok=True)


def require_admin() -> None:
    if os.name != 'posix' or os.geteuid() != 0:
        raise ValueError('Run as root in the existing administrator server session.')


def configure(execute: bool, confirmation: str | None) -> dict:
    if execute and confirmation != CONFIRMATION:
        raise ValueError('Explicit administrator model-egress confirmation is required.')
    require_admin()
    for path in (Path('/opt'), Path('/usr/local/sbin')):
        root_path(path, directory=True)
    root_path(DEPLOY, directory=True, private=True)
    root_path(DEPLOY / 'secrets', directory=True, private=True)
    root_path(DEPLOY / 'deploy.env', private=True)
    root_path(HELPER)
    original = HELPER.read_bytes()
    upgraded = upgrade_helper(original)
    if REPO.resolve(strict=True) != REPO:
        raise ValueError('Unexpected repository path.')
    if KEY.exists() or KEY.is_symlink():
        root_path(KEY)
        if KEY.stat().st_mode & 0o111:
            raise ValueError('Unexpected executable master key file.')
        validate_key(KEY.read_bytes())
    elif ADDITION in original:
        raise ValueError('Previously configured master key is missing; recover it, do not generate a replacement.')
    if OVERLAY.exists() or OVERLAY.is_symlink():
        root_path(OVERLAY, private=True)
        if OVERLAY.read_bytes() != OVERLAY_TEXT.encode():
            raise ValueError('A different personal model overlay exists; refusing to replace it.')
    result = {'mode': 'execute' if execute else 'dry-run', 'operation': 'configure-personal-model-access',
              'masterKey': 'reuse-existing' if KEY.exists() else 'create-new',
              'maintenanceHelper': 'already-configured' if original == upgraded else 'add-fixed-overlay',
              'restartPerformed': False, 'databaseChanged': False, 'sudoersChanged': False}
    if not execute:
        return result
    backup_dir = Path(tempfile.mkdtemp(prefix='personal-model-setup-backup-', dir=DEPLOY))
    backup_dir.chmod(0o700)
    new_file(backup_dir / 'guanxian-maintain', original, 0o600)
    if not KEY.exists():
        new_file(KEY, base64.b64encode(secrets.token_bytes(32)) + b'\n', 0o444)
    # Compose file-backed secrets keep host modes. Root-only parents protect the
    # leaf on the host; read-only 0444 lets the unprivileged Java container read it.
    KEY.chmod(0o444)
    if not OVERLAY.exists():
        new_file(OVERLAY, OVERLAY_TEXT.encode(), 0o600)
    command = ['/usr/bin/docker', 'compose', '--project-name', 'guanxian-single-host',
               '--env-file', str(DEPLOY / 'deploy.env'), '--file', str(REPO / 'compose.single-host.yml'),
               '--file', str(OVERLAY), '--profile', 'app', 'config', '--quiet']
    env = {'PATH': '/usr/sbin:/usr/bin:/sbin:/bin', 'HOME': '/root', 'LANG': 'C.UTF-8'}
    checked = subprocess.run(command, cwd=REPO, env=env, capture_output=True, check=False)
    if checked.returncode:
        # Do not echo Compose diagnostics: they can include interpolated values.
        raise ValueError('Compose validation failed; helper unchanged. Generated key retained for safe retry.')
    if original != upgraded:
        replace_helper(upgraded)
    result['backupDirectory'] = str(backup_dir)
    result['status'] = 'configured-pending-server-restart'
    return result


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--execute', action='store_true')
    parser.add_argument('--confirm-egress', choices=[CONFIRMATION])
    args = parser.parse_args()
    if os.name != 'posix' or os.geteuid() != 0:
        parser.error('Run with sudo python3 from the existing administrator session.')
    import fcntl
    os.umask(0o077)
    with open('/run/lock/guanxian-maintain.lock', 'a') as lock:
        fcntl.flock(lock.fileno(), fcntl.LOCK_EX | fcntl.LOCK_NB)
        try:
            print(json.dumps(configure(args.execute, args.confirm_egress), indent=2))
        except (ValueError, OSError) as error:
            # Errors never contain the encryption key or a provider API key.
            parser.exit(1, 'STOP: ' + str(error) + '\n')


if __name__ == '__main__':
    main()
