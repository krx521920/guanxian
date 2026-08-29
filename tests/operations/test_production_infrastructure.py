from __future__ import annotations

import tempfile
import unittest
from pathlib import Path

from tools.operations.render_alertmanager_config import (
    AlertConfigurationError,
    main,
    render,
    validate_webhook_url,
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
