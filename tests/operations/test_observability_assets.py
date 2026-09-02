from __future__ import annotations

import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]


class ObservabilityAssetsTest(unittest.TestCase):
    def test_monitoring_compose_uses_pinned_images_and_requires_a_token(self) -> None:
        compose = (ROOT / "compose.observability.yml").read_text(encoding="utf-8")

        self.assertIn("prom/prometheus:v3.5.0", compose)
        self.assertIn("quay.io/prometheus/alertmanager:v0.28.1", compose)
        self.assertIn("PROMETHEUS_SCRAPE_TOKEN_FILE:?PROMETHEUS_SCRAPE_TOKEN_FILE is required", compose)
        self.assertIn("prometheus_scrape_token", compose)
        self.assertNotIn("PROMETHEUS_SCRAPE_TOKEN:", compose)
        self.assertIn('profiles: ["observability"]', compose)

    def test_scrape_and_alert_rules_reference_known_micrometer_metrics(self) -> None:
        scrape = (ROOT / "observability" / "prometheus.yml").read_text(encoding="utf-8")
        alerts = (ROOT / "observability" / "alerts.yml").read_text(encoding="utf-8")

        self.assertIn("metrics_path: /actuator/prometheus", scrape)
        self.assertIn("credentials_file: /run/secrets/prometheus_scrape_token", scrape)
        for metric in (
            "up{job=\"guanxian-server\"}",
            "http_server_requests_seconds_count",
            "http_server_requests_seconds_bucket",
            "jvm_memory_used_bytes",
            "hikaricp_connections_active",
            "disk_free_bytes",
            'up{job="postgres"}',
            'up{job="redis"}',
            'up{job="minio"}',
        ):
            self.assertIn(metric, alerts)

    def test_alertmanager_has_a_safe_default_receiver(self) -> None:
        alertmanager = (ROOT / "observability" / "alertmanager.yml").read_text(encoding="utf-8")

        self.assertIn("receiver: operator-log", alertmanager)
        self.assertIn("- name: operator-log", alertmanager)


if __name__ == "__main__":
    unittest.main()
