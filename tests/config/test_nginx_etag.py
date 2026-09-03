"""Real web Nginx with a synthetic upstream on a private, unpublished Docker network."""
import json
import os
from pathlib import Path
import re
import subprocess
import tempfile
import time
import unittest
import uuid


ROOT = Path(__file__).resolve().parents[2]
RUNTIME = re.search(r"^FROM (\S+) AS runtime$", (ROOT / "apps/web/Dockerfile").read_text(), re.MULTILINE)
if RUNTIME is None:
    raise RuntimeError("Cannot identify the actual web Nginx runtime image")


def docker(*arguments, check=True):
    result = subprocess.run(["docker", *arguments], text=True, capture_output=True, timeout=30)
    if check and result.returncode:
        raise RuntimeError(f"Docker command failed: {result.stderr.strip()}")
    return result


@unittest.skipUnless(os.environ.get("GUANXIAN_NGINX_ETAG_TEST") == "1", "opt-in real Nginx ETag test")
class NginxEtagTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        temporary = tempfile.TemporaryDirectory(prefix="guanxian-nginx-etag-")
        cls.addClassCleanup(temporary.cleanup)
        root = Path(temporary.name)
        root.chmod(0o755)
        assets = root / "assets"
        assets.mkdir(mode=0o755)
        cls.asset = "/* synthetic static asset */\n" * 512
        asset_file = assets / "contract.css"
        asset_file.write_text(cls.asset, encoding="utf-8", newline="\n")
        asset_file.chmod(0o644)
        cls.network = f"guanxian-nginx-etag-{uuid.uuid4().hex}"
        docker("network", "create", "--internal", cls.network)
        cls.addClassCleanup(docker, "network", "rm", cls.network)
        fixture = ROOT / "tests/config/fixtures/nginx_etag_backend.py"
        cls.backend = docker("run", "-d", "--network", cls.network, "--network-alias", "server",
            "--read-only", "--cap-drop", "ALL", "--security-opt", "no-new-privileges:true",
            "--user", "65534:65534", "--memory", "128m",
            "--mount", f"type=bind,src={fixture},dst=/fixture.py,readonly",
            "python:3.12-slim-bookworm", "python", "-B", "/fixture.py", "serve").stdout.strip()
        cls.addClassCleanup(docker, "rm", "-f", cls.backend)
        cls.web = docker("run", "-d", "--network", cls.network, "--network-alias", "web",
            "--read-only", "--cap-drop", "ALL", "--security-opt", "no-new-privileges:true",
            "--user", "101:101", "--memory", "128m", "--tmpfs", "/tmp:rw,noexec,nosuid,size=32m",
            "--mount", f"type=bind,src={ROOT / 'apps/web/nginx.conf'},dst=/etc/nginx/conf.d/default.conf,readonly",
            "--mount", f"type=bind,src={root},dst=/usr/share/nginx/html,readonly",
            RUNTIME.group(1)).stdout.strip()
        cls.addClassCleanup(docker, "rm", "-f", cls.web)
        deadline = time.monotonic() + 30
        last_error = "No response"
        while time.monotonic() < deadline:
            try:
                if cls.request("/api/v1/members/readiness")["status"] == 200:
                    return
            except (RuntimeError, ValueError) as error:
                last_error = str(error)
            time.sleep(0.25)
        raise RuntimeError(f"Nginx/upstream did not become ready: {last_error}; "
                           f"{docker('logs', cls.web, check=False).stderr}")

    @classmethod
    def request(cls, path, method="GET", headers=None):
        arguments = {"path": path, "method": method, "headers": {
            "Accept-Encoding": "gzip, br", "Host": "members.test", **(headers or {}),
        }}
        if method == "PUT":
            arguments["body"] = "{}"
            arguments["headers"]["Content-Type"] = "application/json"
        result = docker("exec", cls.backend, "python", "-B", "/fixture.py", "request", json.dumps(arguments))
        return json.loads(result.stdout)

    def test_browser_gzip_request_preserves_strong_etag_and_forwarded_headers(self):
        response = self.request("/api/v1/members/read")
        self.assertEqual(200, response["status"])
        self.assertEqual('"0"', response["headers"].get("etag"))
        self.assertNotIn("content-encoding", response["headers"])
        data = json.loads(response["body"])["data"]
        self.assertIsNone(data["acceptEncoding"])
        self.assertEqual("members.test", data["host"])
        self.assertEqual("http", data["forwardedProto"])
        self.assertEqual("nginx-etag-contract", response["headers"].get("x-request-id"))

    def test_review_preserves_if_match_and_rejects_stale_write(self):
        path = "/api/v1/members/review"
        original = self.request(path)["headers"]["etag"]
        reviewed = self.request(path, "PUT", {"If-Match": original})
        self.assertEqual(200, reviewed["status"])
        self.assertEqual('"1"', reviewed["headers"].get("etag"))
        self.assertNotIn("content-encoding", reviewed["headers"])
        self.assertEqual(original, json.loads(reviewed["body"])["data"]["ifMatch"])
        stale = self.request(path, "PUT", {"If-Match": original})
        self.assertEqual(412, stale["status"])
        self.assertEqual("PRECONDITION_FAILED", json.loads(stale["body"])["code"])
        self.assertEqual("nginx-etag-contract", stale["headers"].get("x-request-id"))
        self.assertEqual(1, json.loads(self.request(path)["body"])["data"]["version"])

    def test_weak_if_match_is_not_promoted_to_a_strong_version(self):
        response = self.request("/api/v1/members/weak-write", "PUT", {"If-Match": 'W/"0"'})
        self.assertEqual(412, response["status"])
        self.assertEqual(0, json.loads(response["body"])["data"]["version"])

    def test_genuinely_weak_upstream_etag_is_not_rewritten(self):
        response = self.request("/api/v1/members/weak")
        self.assertEqual(200, response["status"])
        self.assertEqual('W/"0"', response["headers"].get("etag"))

    def test_missing_upstream_etag_is_not_fabricated(self):
        response = self.request("/api/v1/members/missing")
        self.assertEqual(200, response["status"])
        self.assertNotIn("etag", response["headers"])

    def test_head_keeps_the_strong_validator_without_body(self):
        response = self.request("/api/v1/members/head", "HEAD")
        self.assertEqual(200, response["status"])
        self.assertEqual('"0"', response["headers"].get("etag"))
        self.assertEqual("", response["body"])

    def test_static_asset_compression_remains_enabled(self):
        response = self.request("/assets/contract.css")
        self.assertEqual(200, response["status"])
        self.assertEqual("gzip", response["headers"].get("content-encoding"))
        self.assertEqual(self.asset, response["body"])


if __name__ == "__main__":
    unittest.main()
