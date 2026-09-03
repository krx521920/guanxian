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

from tools.deployment.prepare_single_host import ASSOCIATION_ID, ROOT, bootstrap_sql


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
                probe = subprocess.run(["docker", "exec", cls.container, "pg_isready", "-U", "postgres"],
                                       capture_output=True)
                if probe.returncode == 0:
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
            "-U", "postgres", "-d", database, "-v", "ON_ERROR_STOP=1"],
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
