#!/usr/bin/env python3
"""Render a production Alertmanager webhook config without exposing its secret URL."""

from __future__ import annotations

import argparse
import json
import os
import sys
import tempfile
from pathlib import Path
from urllib.parse import urlsplit


class AlertConfigurationError(ValueError):
    pass


def validate_webhook_url(value: str) -> str:
    cleaned = value.strip()
    parsed = urlsplit(cleaned)
    host = (parsed.hostname or "").casefold()
    if parsed.scheme.casefold() != "https" or not host:
        raise AlertConfigurationError("webhook URL must be an absolute HTTPS URL")
    if parsed.username or parsed.password or parsed.fragment:
        raise AlertConfigurationError("webhook URL must not contain credentials or a fragment")
    if host in {"localhost", "127.0.0.1", "::1"} or host.endswith((".example", ".invalid")):
        raise AlertConfigurationError("loopback and placeholder webhook hosts are forbidden")
    return cleaned


def render(webhook_url: str) -> str:
    quoted_url = json.dumps(validate_webhook_url(webhook_url), ensure_ascii=False)
    return f"""global:
  resolve_timeout: 5m

route:
  receiver: production-on-call
  group_by: [alertname, job, severity]
  group_wait: 30s
  group_interval: 5m
  repeat_interval: 2h

receivers:
  - name: production-on-call
    webhook_configs:
      - url: {quoted_url}
        send_resolved: true
        max_alerts: 0
"""


def write_atomic(output: Path, content: str) -> None:
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
    except Exception:
        temporary.unlink(missing_ok=True)
        raise


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--webhook-url-file", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args(argv)
    try:
        webhook_url = args.webhook_url_file.read_text(encoding="utf-8")
        write_atomic(args.output, render(webhook_url))
    except (OSError, UnicodeError, AlertConfigurationError) as error:
        print(f"alertmanager configuration invalid: {error}", file=sys.stderr)
        return 2
    print(f"alertmanager configuration rendered: {args.output}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
