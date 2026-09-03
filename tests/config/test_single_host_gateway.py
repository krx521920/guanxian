"""Opt-in Nginx configuration validation, no published ports or real certificates."""
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

from tools.deployment.prepare_single_host import ROOT


@unittest.skipUnless(os.environ.get("GUANXIAN_SINGLE_HOST_GATEWAY_TEST") == "1", "opt-in Docker gateway test")
class SingleHostGatewayTests(unittest.TestCase):
    def test_actual_nginx_parses_http_stream_tls_and_acme_routes(self):
        if not shutil.which("openssl"):
            self.fail("openssl is required for the isolated test certificate")
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            config = root / "nginx.conf"
            config.write_text((ROOT / "infrastructure/single-host/nginx.conf.template")
                              .read_text().replace("@DOMAIN@", "host.test"), encoding="utf-8")
            openssl_config = root / "openssl.cnf"
            openssl_config.write_text("[req]\ndistinguished_name=dn\nprompt=no\n[dn]\nCN=host.test\n", encoding="ascii")
            certificate = subprocess.run(["openssl", "req", "-config", str(openssl_config),
                "-x509", "-newkey", "rsa:2048", "-nodes", "-days", "1",
                "-subj", "/CN=host.test", "-addext", "subjectAltName=DNS:host.test",
                "-keyout", str(root / "privkey.pem"), "-out", str(root / "fullchain.pem")],
                text=True, capture_output=True)
            self.assertEqual(0, certificate.returncode, certificate.stderr)
            result = subprocess.run(["docker", "run", "--rm", "--network", "none",
                "--add-host", "redis:127.0.0.1", "--add-host", "minio:127.0.0.1",
                "--mount", f"type=bind,src={config},dst=/etc/nginx/nginx.conf,readonly",
                "--mount", f"type=bind,src={root},dst=/etc/letsencrypt/live/host.test,readonly",
                os.environ.get("GUANXIAN_TEST_NGINX_IMAGE", "nginx:stable-alpine"), "nginx", "-t"],
                text=True, capture_output=True)
            self.assertEqual(0, result.returncode, result.stderr)
            self.assertIn("test is successful", result.stderr)


if __name__ == "__main__":
    unittest.main()
