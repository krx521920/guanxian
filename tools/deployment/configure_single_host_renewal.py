#!/usr/bin/env python3
"""Configure webroot renewal AFTER the single-host gateway is running."""
from __future__ import annotations

import argparse
import os
from pathlib import Path
import shlex
import subprocess
import sys

try:
    from tools.deployment.prepare_single_host import validate_domain, write_new
    from tools.deployment.validate_production_env import load_env_file
except ModuleNotFoundError:
    from prepare_single_host import validate_domain, write_new
    from validate_production_env import load_env_file

ROOT = Path(__file__).resolve().parents[2]


def hook_text(domain: str, env_file: Path, repo: Path) -> str:
    domain = validate_domain(domain)
    env_file = env_file.resolve()
    repo = repo.resolve()
    compose = f"docker compose --env-file {shlex.quote(str(env_file))} -f {shlex.quote(str(repo / 'compose.single-host.yml'))}"
    return f"""#!/bin/sh
set -eu
# Other certificates must not reload this deployment.
[ "${{RENEWED_LINEAGE:-}}" = /etc/letsencrypt/live/{domain} ] || exit 0
{compose} exec -T gateway nginx -t
{compose} exec -T gateway nginx -s reload
"""


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--env-file", type=Path, default=Path("/opt/guanxian-single/deploy.env"))
    args = parser.parse_args(argv)
    try:
        if os.name != "posix" or os.geteuid() != 0:
            raise ValueError("run with sudo on the Linux server")
        values = load_env_file(args.env_file)
        domain = validate_domain(values["SITE_DOMAIN"])
        root = Path(values["SINGLE_HOST_DIR"])
        if root.resolve() != root or root.parent != Path("/opt") or not root.name.startswith("guanxian-"):
            raise ValueError("deployment directory must be a real /opt/guanxian-* directory")
        if args.env_file.resolve() != root / "deploy.env":
            raise ValueError("env file does not belong to the deployment directory")
        hook = Path("/etc/letsencrypt/renewal-hooks/deploy/guanxian-single-host.sh")
        if hook.exists() or hook.is_symlink():
            raise ValueError("renewal hook already exists; inspect it instead of overwriting it")
        subprocess.run(["certbot", "reconfigure", "--cert-name", domain,
                        "--authenticator", "webroot", "--webroot-path", str(root / "acme-webroot")], check=True)
        hook.parent.mkdir(parents=True, exist_ok=True)
        write_new(hook, hook_text(domain, args.env_file, ROOT), 0o700)
        subprocess.run(["certbot", "renew", "--cert-name", domain,
                        "--dry-run", "--run-deploy-hooks"], check=True)
        print("Webroot renewal and the gateway reload hook passed the dry run.")
        return 0
    except (ValueError, KeyError, OSError, subprocess.CalledProcessError) as exception:
        print(f"Renewal setup stopped: {exception}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
