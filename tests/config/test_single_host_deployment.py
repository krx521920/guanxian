from __future__ import annotations

import json
import os
from pathlib import Path
import secrets
import subprocess
import tempfile
import unittest
import uuid

from tools.deployment.prepare_single_host import (
    IMAGE_SOURCES, ROOT, bootstrap_sql, generate, realm,
    validate_domain, validate_password, validate_username,
)
from tools.deployment.configure_single_host_renewal import hook_text


class SingleHostPreparationTests(unittest.TestCase):
    def setUp(self):
        self.password = secrets.token_urlsafe(24)
        self.subject = str(uuid.uuid4())
        self.images = {key: value.split(":")[0] + "@sha256:" + "a" * 64
                       for key, value in IMAGE_SOURCES.items()}

    def test_accepts_operator_domain(self):
        self.assertEqual("guanxian.miraphant.com", validate_domain("guanxian.miraphant.com"))

    def test_rejects_hostname_injection_and_non_domain(self):
        for value in ("https://host.test", "host.test:443", "host.test/identity", "x;return 200;",
                      "host.test\nserver {}", "*.host.test", "127.0.0.1", "-host.test", "x..test"):
            with self.subTest(value=value), self.assertRaises(ValueError):
                validate_domain(value)

    def test_rejects_shared_or_sql_shaped_username(self):
        for value in ("admin", "ci-operator", "demo", "operator'; --", "a", "operator\n"):
            with self.subTest(value=value), self.assertRaises(ValueError):
                validate_username(value)

    def test_rejects_weak_password(self):
        for value in ("short", "a" * 24, "abCDefghijk123456\n"):
            with self.subTest(), self.assertRaises(ValueError):
                validate_password(value)

    def test_realm_has_only_real_operator_and_exact_pkce_callback(self):
        data = realm("host.test", "operator-one", self.password, self.subject)
        self.assertEqual("all", data["sslRequired"])
        self.assertFalse(data["registrationAllowed"])
        self.assertTrue(data["bruteForceProtected"])
        self.assertEqual(1, len(data["users"]))
        self.assertEqual(self.subject, data["users"][0]["id"])
        self.assertTrue(data["users"][0]["credentials"][0]["temporary"])
        client = data["clients"][0]
        self.assertEqual(["https://host.test/auth/callback"], client["redirectUris"])
        self.assertEqual("S256", client["attributes"]["pkce.code.challenge.method"])
        self.assertFalse(client["directAccessGrantsEnabled"])
        self.assertFalse(client["implicitFlowEnabled"])

    def test_bootstrap_is_transactional_first_install_only_and_audited(self):
        sql = bootstrap_sql("operator-one", self.subject)
        for text in ("BEGIN;", "COMMIT;", "LOCK TABLE user_account", "version='23'",
                     "SELECT 1 FROM user_account", "SELECT 1 FROM revoked_identity_subject",
                     "INSERT INTO audit_log", "INITIAL_OPERATOR_BINDING"):
            self.assertIn(text, sql)
        self.assertNotIn("UPDATE user_account", sql)
        self.assertNotIn("INSERT INTO enterprise", sql)
        self.assertNotIn(self.password, sql)

    def test_rejects_malformed_subject(self):
        with self.assertRaises(ValueError):
            bootstrap_sql("operator-one", "not-a-uuid")

    def test_generation_contains_no_secret_in_env_or_manifest(self):
        with tempfile.TemporaryDirectory() as temporary:
            output = Path(temporary) / "deployment"
            generate(output, "host.test", "operator-one", self.password, "release-1", self.images)
            env = (output / "deploy.env").read_text()
            manifest = (output / "manifest.json").read_text()
            for file in (output / "secrets").iterdir():
                if file.name not in {"redis.conf", "guanxian-realm.json"}:
                    value = file.read_text()
                    self.assertNotIn(value, env)
                    self.assertNotIn(value, manifest)
            self.assertNotIn(self.password, env + manifest)
            data = json.loads((output / "secrets/guanxian-realm.json").read_text())
            self.assertEqual(json.loads(manifest)["subject"], data["users"][0]["id"])
            self.assertIn("rediss://", (output / "secrets/storage_redis_url").read_text())
            if os.name == "posix":
                self.assertEqual(0o700, output.stat().st_mode & 0o777)
                self.assertEqual(0o700, (output / "secrets").stat().st_mode & 0o777)
                self.assertEqual(0o444, (output / "secrets/postgres_password").stat().st_mode & 0o777)
                self.assertEqual(0o600, (output / "deploy.env").stat().st_mode & 0o777)

    def test_initialization_never_overwrites_an_existing_directory(self):
        with tempfile.TemporaryDirectory() as temporary:
            output = Path(temporary) / "deployment"
            output.mkdir()
            sentinel = output / "keep"
            sentinel.write_text("user-owned")
            with self.assertRaises(ValueError):
                generate(output, "host.test", "operator-one", self.password, "release-1", self.images)
            self.assertEqual("user-owned", sentinel.read_text())

    def test_requires_digest_pins_before_creating_secrets(self):
        with tempfile.TemporaryDirectory() as temporary:
            output = Path(temporary) / "deployment"
            with self.assertRaises(ValueError):
                generate(output, "host.test", "operator-one", self.password, "release-1", IMAGE_SOURCES)
            self.assertFalse(output.exists())

    def test_rejects_release_injection(self):
        with tempfile.TemporaryDirectory() as temporary, self.assertRaises(ValueError):
            generate(Path(temporary) / "deploy", "host.test", "operator-one", self.password,
                     "tag\nDANGEROUS=true", self.images)

    def test_nginx_has_acme_route_without_callback_collision_or_query_logging(self):
        template = (ROOT / "infrastructure/single-host/nginx.conf.template").read_text()
        self.assertIn("location ^~ /.well-known/acme-challenge/", template)
        self.assertIn("location ^~ /identity/realms/guanxian/", template)
        self.assertNotIn("location /auth", template)
        self.assertIn("location /identity { return 404; }", template)
        self.assertIn("location /actuator { return 404; }", template)
        self.assertIn("listen 9443 ssl", template)
        self.assertIn("listen 6380 ssl", template)
        self.assertNotIn("$request\"", template)
        self.assertIn('"$request_method $uri $server_protocol"', template)

    def test_renewal_hook_is_scoped_quotes_paths_and_reloads_only_gateway(self):
        text = hook_text("host.test", Path("/deployment dir/deploy.env"), Path("/source dir"))
        self.assertIn("RENEWED_LINEAGE", text)
        self.assertIn("/etc/letsencrypt/live/host.test", text)
        self.assertIn("nginx -t", text)
        self.assertIn("nginx -s reload", text)
        self.assertNotIn(" stop ", text)
        self.assertNotIn(" down ", text)
        self.assertIn("'", text)

    def test_compose_has_no_host_database_ports_or_placeholder_security(self):
        compose = (ROOT / "compose.single-host.yml").read_text()
        self.assertEqual(1, compose.count("    ports:"))
        self.assertIn("ports: ['80:80', '443:443']", compose)
        for forbidden in ("start-dev", "tests/e2e", "tests/identity", "privileged:", "docker.sock", "latest"):
            self.assertNotIn(forbidden, compose)
        for required in ("GUANXIAN_SECURITY_MODE: jwt", "GUANXIAN_SEED_DEMO_DATA: 'false'",
                         "GUANXIAN_STORAGE_SCAN_MODE: clamav", "GUANXIAN_STORAGE_RATE_LIMIT_ENABLED: 'true'",
                         "GUANXIAN_JWT_BOOTSTRAP_SYSTEM_ADMIN_SUBJECTS: ''",
                         "configtree:/run/secrets/", "internal: true", "max-file:"):
            self.assertIn(required, compose)

    def test_docker_compose_renders_with_generated_environment(self):
        # Rendering requires the Compose CLI, NOT a Docker daemon or real secrets.
        if not __import__('shutil').which("docker"):
            self.skipTest("Docker CLI not installed")
        with tempfile.TemporaryDirectory() as temporary:
            output = Path(temporary) / "deployment"
            generate(output, "host.test", "operator-one", self.password, "release-1", self.images)
            result = subprocess.run(["docker", "compose", "--env-file", str(output / "deploy.env"),
                "-f", str(ROOT / "compose.single-host.yml"), "--profile", "app", "config", "--format", "json"],
                text=True, capture_output=True)
            self.assertEqual(0, result.returncode, result.stderr)
            data = json.loads(result.stdout)
            for name, service in data["services"].items():
                if name != "gateway":
                    self.assertFalse(service.get("ports"), name)
                self.assertGreater(int(service["mem_limit"]), 0, name)
            self.assertNotIn(self.password, result.stdout)
            self.assertEqual("production", data["services"]["server"]["environment"]["SPRING_PROFILES_ACTIVE"])
            self.assertTrue(data["networks"]["data"]["internal"])


if __name__ == "__main__":
    unittest.main()
