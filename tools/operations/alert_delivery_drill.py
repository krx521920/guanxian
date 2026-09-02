#!/usr/bin/env python3
"""Send firing/resolved alerts and verify both in a real receiver evidence log."""

from __future__ import annotations

import argparse
import json
import sys
import time
import uuid
from datetime import datetime, timedelta, timezone
from pathlib import Path
from urllib.error import HTTPError, URLError
from urllib.parse import urlsplit
from urllib.request import Request, urlopen


class AlertDrillError(ValueError):
    pass


def validate_alertmanager_url(value: str) -> str:
    parsed = urlsplit(value.strip())
    host = (parsed.hostname or "").casefold()
    if not host or parsed.username or parsed.password or parsed.query or parsed.fragment:
        raise AlertDrillError("Alertmanager URL shape is invalid")
    if parsed.scheme == "http" and host not in {"localhost", "127.0.0.1", "::1"}:
        raise AlertDrillError("non-loopback Alertmanager must use HTTPS")
    if parsed.scheme not in {"http", "https"}:
        raise AlertDrillError("Alertmanager URL must use HTTP(S)")
    return value.strip().rstrip("/")


def post_alert(base_url: str, drill_id: str, *, resolved: bool, timeout: float) -> None:
    now = datetime.now(timezone.utc)
    payload = [{
        "labels": {"alertname": "GuanxianDeliveryDrill", "severity": "info", "drill_id": drill_id},
        "annotations": {"summary": "管线平台告警发送与恢复闭环演练"},
        "startsAt": (now - timedelta(seconds=1)).isoformat(),
        "endsAt": (now if resolved else now + timedelta(hours=1)).isoformat(),
        "generatorURL": "https://operations.guanxian.invalid/controlled-alert-drill",
    }]
    request = Request(
        f"{base_url}/api/v2/alerts",
        data=json.dumps(payload).encode("utf-8"),
        headers={"Content-Type": "application/json"},
        method="POST",
    )
    try:
        with urlopen(request, timeout=timeout) as response:
            if response.status not in {200, 202}:
                raise AlertDrillError(f"Alertmanager returned HTTP {response.status}")
    except (HTTPError, URLError, TimeoutError) as error:
        raise AlertDrillError(f"Alertmanager request failed: {error}") from error


def evidence_statuses(path: Path, drill_id: str) -> set[str]:
    if not path.is_file():
        raise AlertDrillError(f"receiver evidence file does not exist: {path}")
    statuses: set[str] = set()
    for number, line in enumerate(path.read_text(encoding="utf-8").splitlines(), start=1):
        if not line.strip():
            continue
        try:
            payload = json.loads(line)
        except json.JSONDecodeError as error:
            raise AlertDrillError(f"receiver evidence line {number} is invalid JSON") from error
        if not isinstance(payload, dict) or payload.get("status") not in {"firing", "resolved"}:
            continue
        alerts = payload.get("alerts")
        if isinstance(alerts, list) and any(
            isinstance(alert, dict)
            and isinstance(alert.get("labels"), dict)
            and alert["labels"].get("drill_id") == drill_id
            for alert in alerts
        ):
            statuses.add(str(payload["status"]))
    return statuses


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--alertmanager-url", default="http://127.0.0.1:9093")
    parser.add_argument("--receiver-evidence", type=Path, required=True, help="NDJSON written by the configured real webhook receiver")
    parser.add_argument("--firing-wait-seconds", type=float, default=35)
    parser.add_argument("--resolved-wait-seconds", type=float, default=310)
    parser.add_argument("--request-timeout-seconds", type=float, default=10)
    parser.add_argument("--report", type=Path, required=True)
    parser.add_argument("--execute", action="store_true")
    args = parser.parse_args(argv)
    drill_id = str(uuid.uuid4())
    try:
        base_url = validate_alertmanager_url(args.alertmanager_url)
        if not args.execute:
            print(json.dumps({"mode": "dry-run", "operation": "alert-delivery-drill", "alertmanager": base_url, "evidence": str(args.receiver_evidence)}, ensure_ascii=False, indent=2))
            return 0
        if args.firing_wait_seconds < 0 or args.resolved_wait_seconds < 0:
            raise AlertDrillError("wait durations cannot be negative")
        started = datetime.now(timezone.utc)
        post_alert(base_url, drill_id, resolved=False, timeout=args.request_timeout_seconds)
        time.sleep(args.firing_wait_seconds)
        post_alert(base_url, drill_id, resolved=True, timeout=args.request_timeout_seconds)
        time.sleep(args.resolved_wait_seconds)
        statuses = evidence_statuses(args.receiver_evidence, drill_id)
        if statuses != {"firing", "resolved"}:
            raise AlertDrillError(f"receiver evidence is incomplete: observed {sorted(statuses)}")
        report = {
            "schemaVersion": 1,
            "operation": "alert-delivery-drill",
            "status": "verified",
            "drillId": drill_id,
            "startedAt": started.isoformat(),
            "completedAt": datetime.now(timezone.utc).isoformat(),
            "receiverEvidence": str(args.receiver_evidence.expanduser().resolve()),
            "observedStatuses": ["firing", "resolved"],
        }
        target = args.report.expanduser().resolve()
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(json.dumps(report, ensure_ascii=False, indent=2, sort_keys=True) + "\n", encoding="utf-8")
        print(json.dumps(report, ensure_ascii=False, indent=2))
        return 0
    except (AlertDrillError, OSError, UnicodeError) as error:
        print(f"ERROR: {error}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
