from __future__ import annotations

import json
import tempfile
import unittest
from pathlib import Path

from tools.operations.render_alertmanager_config import (
    AlertConfigurationError,
    main,
    render,
    validate_webhook_url,
)
from tools.operations.render_minio_prometheus_target import (
    MinioTargetError,
    render as render_minio_target,
    target_from_endpoint,
)
from tools.operations.alert_delivery_drill import (
    AlertDrillError,
    evidence_statuses,
    validate_alertmanager_url,
)


ROOT = Path(__file__).resolve().parents[2]


class ProductionTopologyTests(unittest.TestCase):
    def test_only_gateway_has_public_wildcard_ports(self) -> None:
        compose = (ROOT / "compose.production.yml").read_text(encoding="utf-8")
        self.assertIn("app-internal:\n    internal: true", compose)
        self.assertIn("data-internal:\n    internal: true", compose)
        self.assertIn('"${GATEWAY_HTTPS_PORT:-443}:443"', compose)
        self.assertNotIn('"${SERVER_PORT:-8080}:8080"', compose)
        self.assertNotIn('"${POSTGRES_PORT:-5432}:5432"', compose)
        self.assertIn('"127.0.0.1:${GRAFANA_PORT:-3000}:3000"', compose)

    def test_tls_gateway_is_fail_closed(self) -> None:
        gateway = (ROOT / "infrastructure" / "gateway" / "nginx.conf").read_text(
            encoding="utf-8"
        )
        self.assertIn("return 308 https://$host$request_uri", gateway)
        self.assertIn("ssl_protocols TLSv1.2 TLSv1.3", gateway)
        self.assertIn("Strict-Transport-Security", gateway)
        self.assertIn("location ^~ /actuator/", gateway)
        self.assertIn("client_max_body_size 21m", gateway)

    def test_central_log_pipeline_keeps_request_id(self) -> None:
        compose = (ROOT / "compose.production.yml").read_text(encoding="utf-8")
        promtail = (ROOT / "observability" / "promtail.yml").read_text(encoding="utf-8")
        datasources = (ROOT / "observability" / "grafana-datasources.yml").read_text(
            encoding="utf-8"
        )
        self.assertIn("grafana/loki:3.4.4", compose)
        self.assertIn("grafana/promtail:3.4.4", compose)
        self.assertIn("requestId: requestId", promtail)
        self.assertIn("http://loki:3100", datasources)

    def test_database_wal_archive_and_dependency_exporters_are_explicit(self) -> None:
        compose = (ROOT / "compose.production.yml").read_text(encoding="utf-8")
        prometheus = (ROOT / "observability" / "prometheus.yml").read_text(encoding="utf-8")
        for expected in (
            "archive_mode=on",
            'postgres-wal-init:',
            'chown postgres:postgres /wal-archive',
            "postgres-wal-archive:/var/lib/postgresql/wal-archive",
            "quay.io/prometheuscommunity/postgres-exporter:v0.19.1",
            "oliver006/redis_exporter:v1.69.0",
            "DATA_SOURCE_PASS_FILE: /run/secrets/postgres_password",
            "REDIS_PASSWORD_FILE: /run/secrets/redis_exporter_passwords",
        ):
            self.assertIn(expected, compose)
        for job in ("job_name: postgres", "job_name: redis", "job_name: minio"):
            self.assertIn(job, prometheus)
        self.assertIn("credentials_file: /run/secrets/minio_prometheus_bearer_token", prometheus)
        self.assertIn("job_name: minio\n    scheme: https", prometheus)

    def test_production_readiness_group_names_all_required_dependencies(self) -> None:
        production = (ROOT / "apps" / "server" / "bootstrap" / "src" / "main" / "resources" / "application-production.yml").read_text(encoding="utf-8")
        self.assertIn("include: db,minioObjectStorage,redisAttachmentRateLimiter,oidcJwkSet", production)


class MinioPrometheusTargetTests(unittest.TestCase):
    def test_renders_https_target_without_credentials(self) -> None:
        self.assertEqual("storage.operator.test:9443", target_from_endpoint("https://storage.operator.test:9443"))
        payload = render_minio_target("https://storage.operator.test")
        self.assertIn('"storage.operator.test:443"', payload)
        self.assertNotIn("password", payload.casefold())

    def test_rejects_unsafe_endpoint_shapes(self) -> None:
        for endpoint in ("http://storage.operator.test", "https://user:pw@storage.operator.test", "https://storage.operator.test/minio"):
            with self.subTest(endpoint=endpoint), self.assertRaises(MinioTargetError):
                target_from_endpoint(endpoint)


class AlertDeliveryEvidenceTests(unittest.TestCase):
    def test_remote_plain_http_alertmanager_is_rejected(self) -> None:
        self.assertEqual("http://127.0.0.1:9093", validate_alertmanager_url("http://127.0.0.1:9093/"))
        with self.assertRaises(AlertDrillError):
            validate_alertmanager_url("http://alertmanager.internal:9093")

    def test_requires_firing_and_resolved_for_same_drill_id(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            evidence = Path(directory) / "events.ndjson"
            entries = [
                {"status": "firing", "alerts": [{"labels": {"drill_id": "drill-1"}}]},
                {"status": "resolved", "alerts": [{"labels": {"drill_id": "drill-1"}}]},
                {"status": "resolved", "alerts": [{"labels": {"drill_id": "other"}}]},
            ]
            evidence.write_text("\n".join(json.dumps(item) for item in entries), encoding="utf-8")
            self.assertEqual({"firing", "resolved"}, evidence_statuses(evidence, "drill-1"))


class AlertmanagerRenderTests(unittest.TestCase):
    def test_rejects_non_https_and_placeholder_destinations(self) -> None:
        for value in (
            "http://alerts.example.net/hook",
            "https://localhost/hook",
            "https://alerts.invalid/hook",
            "https://user:password@alerts.example.net/hook",
        ):
            with self.subTest(value=value), self.assertRaises(AlertConfigurationError):
                validate_webhook_url(value)

    def test_renders_real_webhook_receiver(self) -> None:
        rendered = render("https://alerts.operator.test/hooks/guanxian")
        self.assertIn("receiver: production-on-call", rendered)
        self.assertIn("webhook_configs:", rendered)
        self.assertIn("send_resolved: true", rendered)

    def test_cli_writes_config_without_printing_secret(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            secret = root / "webhook.secret"
            output = root / "runtime" / "alertmanager.yml"
            secret.write_text("https://alerts.operator.test/hooks/sensitive", encoding="utf-8")
            self.assertEqual(0, main(["--webhook-url-file", str(secret), "--output", str(output)]))
            self.assertIn("hooks/sensitive", output.read_text(encoding="utf-8"))


if __name__ == "__main__":
    unittest.main()
