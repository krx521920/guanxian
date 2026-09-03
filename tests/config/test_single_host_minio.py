"""Opt-in real single-host MinIO bootstrap/retry, with only random temporary data."""
import json
import os
from pathlib import Path
import secrets
import subprocess
import tempfile
import unittest
import uuid

from tools.deployment.prepare_single_host import IMAGE_SOURCES, ROOT, generate


@unittest.skipUnless(os.environ.get("GUANXIAN_SINGLE_HOST_MINIO_TEST") == "1", "opt-in real MinIO bootstrap test")
class SingleHostMinioTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.secret_values = []
        cls.project = "guanxian-minio-init-test-" + uuid.uuid4().hex
        temporary = tempfile.TemporaryDirectory(prefix=cls.project)
        cls.addClassCleanup(temporary.cleanup)
        cls.directory = Path(temporary.name) / "deployment"
        images = {key: value.split(":")[0] + "@sha256:" + "a" * 64
                  for key, value in IMAGE_SOURCES.items()}
        for key in ("MINIO_IMAGE", "MINIO_MC_IMAGE"):
            images[key] = cls.command("docker", "image", "inspect", IMAGE_SOURCES[key],
                                      "--format", "{{index .RepoDigests 0}}").stdout.strip()
        password = secrets.token_urlsafe(24)
        generate(cls.directory, "host.test", "operator-one", password, "minio-test", images)
        cls.secret_values = [password] + [
            (cls.directory / "secrets" / name).read_text() for name in (
                "minio_root_user", "minio_root_password", "storage_access_key", "storage_secret_key")]
        cls.prefix = ["docker", "compose", "--project-name", cls.project,
                      "--env-file", str(cls.directory / "deploy.env"),
                      "-f", str(ROOT / "compose.single-host.yml")]
        rendered = json.loads(cls.compose("config", "--format", "json").stdout)
        if not rendered["networks"]["data"].get("internal"):
            raise RuntimeError("MinIO test requires the private data network")
        for service in ("minio", "minio-init"):
            if rendered["services"][service].get("ports"):
                raise RuntimeError("MinIO test must not publish ports")
        cls.addClassCleanup(cls.cleanup_project)
        cls.compose("up", "-d", "--wait", "--wait-timeout", "120", "minio", timeout=150)

    @classmethod
    def command(cls, *arguments, timeout=30):
        result = subprocess.run(arguments, capture_output=True, text=True, encoding="utf-8",
                                errors="replace", timeout=timeout)
        if result.returncode:
            # Never echo a full command, environment or unredacted mc diagnostics.
            output = result.stdout + result.stderr
            for value in cls.secret_values:
                output = output.replace(value, "[redacted]")
            raise RuntimeError(f"Isolated MinIO test command failed ({result.returncode}): {output[-3000:]}")
        return result

    @classmethod
    def compose(cls, *arguments, timeout=30):
        return cls.command(*cls.prefix, *arguments, timeout=timeout)

    @classmethod
    def cleanup_project(cls):
        # Only this UUID-named test project; never use a real deployment env/project.
        if not cls.project.startswith("guanxian-minio-init-test-") or not cls.directory.is_dir():
            raise RuntimeError("Invalid cleanup target")
        cls.compose("down", "--volumes", "--timeout", "5", timeout=60)

    def bootstrap(self):
        self.compose("up", "--no-deps", "--force-recreate", "--abort-on-container-exit",
                     "--exit-code-from", "minio-init", "minio-init", timeout=90)
        container = self.compose("ps", "--all", "--quiet", "minio-init").stdout.strip()
        self.assertRegex(container, r"^[a-f0-9]{64}$")
        state = json.loads(self.command("docker", "inspect", "--format",
            '{{json .State}}', container).stdout)
        self.assertEqual("exited", state["Status"])
        self.assertEqual(0, state["ExitCode"])
        self.assertFalse(state["OOMKilled"])
        memory = int(self.command("docker", "inspect", "--format",
                                 "{{.HostConfig.Memory}}", container).stdout.strip())
        self.assertEqual(512 * 1024 * 1024, memory)

    def app_client(self, script):
        login = 'export MC_HOST_app="http://$(cat /run/secrets/storage_access_key):$(cat /run/secrets/storage_secret_key)@minio:9000"\n'
        return self.compose("run", "--rm", "--no-deps", "--entrypoint", "/bin/sh", "minio-init",
                            "-ec", login + script, timeout=60)

    def test_bounded_bootstrap_and_repeat_preserve_private_bucket_and_app_access(self):
        before = {path.name: path.read_bytes() for path in (self.directory / "secrets").iterdir()}
        self.bootstrap()
        self.app_client("printf 'minio-bootstrap-sentinel' | mc pipe app/guanxian-private/sentinel.txt >/dev/null")
        self.assertEqual("minio-bootstrap-sentinel",
                         self.app_client("mc cat app/guanxian-private/sentinel.txt").stdout.strip())
        # Retrying this one-shot must not delete existing objects or rotate credentials.
        self.bootstrap()
        self.assertEqual("minio-bootstrap-sentinel",
                         self.app_client("mc cat app/guanxian-private/sentinel.txt").stdout.strip())
        self.app_client("mc rm app/guanxian-private/sentinel.txt >/dev/null")
        # The application account must remain non-administrative after both runs.
        # mc --json admin info can exit zero while returning an error record.
        # Accept either process outcome, but require the explicit denial response;
        # empty output, OOM, transport errors and successful admin access must fail.
        denied = self.app_client("mc --json admin info app || true")
        denial = json.loads(denied.stdout)
        self.assertEqual("error", denial["status"])
        self.assertEqual("Access Denied.", denial["error"])
        root_login = 'export MC_HOST_local="http://$(cat /run/secrets/minio_root_user):$(cat /run/secrets/minio_root_password)@minio:9000"\n'
        permission = self.compose("run", "--rm", "--no-deps", "--entrypoint", "/bin/sh", "minio-init",
                                  "-ec", root_login + "mc --json anonymous get local/guanxian-private", timeout=60)
        policy = json.loads(permission.stdout)
        self.assertEqual("success", policy["status"])
        self.assertEqual("private", policy["permission"])
        self.assertFalse(policy.get("anonymous"))
        after = {path.name: path.read_bytes() for path in (self.directory / "secrets").iterdir()}
        self.assertTrue(before == after, "Initialization changed a credential file")


if __name__ == "__main__":
    unittest.main()
