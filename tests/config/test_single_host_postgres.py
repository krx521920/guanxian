"""Opt-in real PostgreSQL tests for the NEW deployment's identity bootstrap.

Uses an ephemeral container, network=none, no host ports, no persistent volumes.
Set GUANXIAN_SINGLE_HOST_DB_TEST=1; never points to the operator's database.
"""
from __future__ import annotations

import os
from pathlib import Path
import subprocess
import time
import unittest
import uuid
from unittest import mock

from tools.deployment.prepare_single_host import ASSOCIATION_ID, ROOT, bootstrap_sql


def postgres_ready(container: str) -> bool:
    # The official entrypoint's temporary init server only accepts Unix sockets.
    # Probe container-local TCP so CREATE DATABASE cannot race its shutdown.
    # https://github.com/docker-library/postgres/blob/master/docker-entrypoint.sh
    return subprocess.run(
        ["docker", "exec", container, "pg_isready", "-h", "127.0.0.1",
         "-U", "postgres", "-d", "postgres", "-t", "1"],
        capture_output=True, timeout=5,
    ).returncode == 0


class SingleHostReadinessTests(unittest.TestCase):
    def test_socket_only_initialization_is_not_reported_ready(self):
        def socket_only(command, **kwargs):
            return subprocess.CompletedProcess(command, 1 if "-h" in command else 0)

        with mock.patch.object(subprocess, "run", side_effect=socket_only) as run:
            self.assertFalse(postgres_ready("isolated-test-container"))
        command = run.call_args.args[0]
        self.assertEqual("127.0.0.1", command[command.index("-h") + 1])

    def test_only_successful_tcp_probe_is_ready(self):
        for code in (0, 1, 2, 3, 125):
            with self.subTest(exit_code=code), mock.patch.object(
                subprocess, "run", return_value=subprocess.CompletedProcess([], code)
            ):
                self.assertEqual(code == 0, postgres_ready("isolated-test-container"))

    def test_docker_timeout_is_not_masked_as_readiness(self):
        with mock.patch.object(subprocess, "run", side_effect=subprocess.TimeoutExpired("docker", 5)):
            with self.assertRaises(subprocess.TimeoutExpired):
                postgres_ready("isolated-test-container")


@unittest.skipUnless(os.environ.get("GUANXIAN_SINGLE_HOST_DB_TEST") == "1", "opt-in isolated Docker database test")
class SingleHostPostgresTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.container = "guanxian-single-bootstrap-test-" + uuid.uuid4().hex[:12]
        cls.started = False
        try:
            subprocess.run(["docker", "run", "--detach", "--rm", "--name", cls.container,
                "--network", "none", "--tmpfs", "/var/lib/postgresql/data",
                "--env", "POSTGRES_HOST_AUTH_METHOD=trust", "postgres:16-alpine"],
                check=True, capture_output=True, text=True)
            cls.started = True
            for _ in range(60):
                if postgres_ready(cls.container):
                    break
                time.sleep(0.25)
            else:
                raise RuntimeError("isolated PostgreSQL did not become ready")
            cls.sql("CREATE DATABASE bootstrap_template", database="postgres")
            files = sorted((ROOT / "apps/server/bootstrap/src/main/resources/db/migration").glob("V*__*.sql"),
                           key=lambda p: int(p.name.split("__")[0][1:]))
            for path in files:
                cls.sql(path.read_text(encoding="utf-8"), database="bootstrap_template")
            # Apply actual V1-V23 SQL above; this minimal marker tests our bootstrap
            # guard, NOT the Flyway runner (covered by the existing Java tests).
            cls.sql("CREATE TABLE flyway_schema_history(version text, success boolean); "
                    "INSERT INTO flyway_schema_history VALUES ('23', true)", database="bootstrap_template")
        except BaseException:
            cls.tearDownClass()
            raise

    @classmethod
    def tearDownClass(cls):
        if cls.started:
            subprocess.run(["docker", "stop", cls.container], check=True, capture_output=True)
            cls.started = False

    @classmethod
    def sql(cls, value, database="bootstrap_template", *, check=True):
        result = subprocess.run(["docker", "exec", "-i", cls.container, "psql", "-X", "-At",
            "-h", "127.0.0.1", "-U", "postgres", "-d", database, "-v", "ON_ERROR_STOP=1"],
            input=value, encoding="utf-8", capture_output=True)
        if check and result.returncode:
            raise AssertionError(result.stderr)
        return result

    def setUp(self):
        self.database = "bootstrap_case_" + uuid.uuid4().hex[:12]
        self.sql(f"CREATE DATABASE {self.database} TEMPLATE bootstrap_template", database="postgres")
        self.subject = str(uuid.uuid4())

    def query(self, query, *, check=True):
        return self.sql(query, database=self.database, check=check)

    def test_new_binding_and_audit_written_together_without_members(self):
        self.query(bootstrap_sql("operator-one", self.subject))
        result = self.query("SELECT (SELECT count(*) FROM user_account), "
                            "(SELECT count(*) FROM audit_log WHERE action='INITIAL_OPERATOR_BINDING'), "
                            "(SELECT count(*) FROM enterprise)")
        self.assertEqual("1|1|0", result.stdout.strip())
        self.assertEqual(self.subject, self.query("SELECT external_subject FROM user_account").stdout.strip())

    def test_repeat_does_not_overwrite_or_add_an_account(self):
        self.query(bootstrap_sql("operator-one", self.subject))
        self.assertNotEqual(0, self.query(bootstrap_sql("operator-two", str(uuid.uuid4())), check=False).returncode)
        self.assertEqual("1", self.query("SELECT count(*) FROM user_account").stdout.strip())
        self.assertEqual("operator-one", self.query("SELECT username FROM user_account").stdout.strip())

    def test_missing_migration_is_fail_closed(self):
        self.query("DELETE FROM flyway_schema_history")
        self.assertNotEqual(0, self.query(bootstrap_sql("operator-one", self.subject), check=False).returncode)
        self.assertEqual("0", self.query("SELECT count(*) FROM user_account").stdout.strip())

    def test_inactive_association_prevents_initial_binding(self):
        self.query(f"UPDATE association SET status='INACTIVE' WHERE id='{ASSOCIATION_ID}'")
        self.assertNotEqual(0, self.query(bootstrap_sql("operator-one", self.subject), check=False).returncode)
        self.assertEqual("0", self.query("SELECT count(*) FROM user_account").stdout.strip())

    def test_audit_failure_rolls_back_binding(self):
        self.query("ALTER TABLE audit_log ADD CONSTRAINT test_reject_bootstrap CHECK(action <> 'INITIAL_OPERATOR_BINDING')")
        self.assertNotEqual(0, self.query(bootstrap_sql("operator-one", self.subject), check=False).returncode)
        self.assertEqual("0", self.query("SELECT count(*) FROM user_account").stdout.strip())


if __name__ == "__main__":
    unittest.main()
