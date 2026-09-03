"""Execute the real startup script with command doubles; no services are started.

PowerShell is required (available on the GitHub Ubuntu runner). The successful
oneshot fixture is deliberately invisible to Compose ps without --all, matching
the CI regression. Real dependency/browser coverage remains in browser-e2e.
"""
from __future__ import annotations

import json
import os
from pathlib import Path
import shutil
import subprocess
import unittest


ROOT = Path(__file__).resolve().parents[2]
SEED_ID = "a" * 64
COMPLETED = {"Status": "exited", "Running": False, "OOMKilled": False, "ExitCode": 0}
HARNESS = r"""
$ErrorActionPreference = 'Stop'
$global:SeedCase = $env:GUANXIAN_STARTUP_TEST_CASE | ConvertFrom-Json
$global:TestCalls = [System.Collections.Generic.List[object]]::new()
function global:docker {
  $callArguments = @($args)
  $global:TestCalls.Add($callArguments)
  $global:LASTEXITCODE = 0
  if ($callArguments[0] -eq 'inspect') {
    $global:LASTEXITCODE = [int]$global:SeedCase.inspect_exit
    if ($callArguments -contains '{{.State.ExitCode}}') {
      # Faithful response for the old exit-code-only implementation.
      $state = $global:SeedCase.inspect_output | ConvertFrom-Json
      Write-Output ([string]$state.ExitCode)
    } else {
      Write-Output $global:SeedCase.inspect_output
    }
    return
  }
  if ($callArguments -contains 'ps' -and $callArguments[-1] -eq 'e2e-seed') {
    $global:LASTEXITCODE = [int]$global:SeedCase.ps_exit
    if ($callArguments -contains '--all' -or $callArguments -contains '-a') {
      $global:SeedCase.ps_output | ForEach-Object { Write-Output $_ }
    }
    return
  }
  if ($callArguments -contains $global:SeedCase.fail_command) {
    $global:LASTEXITCODE = 1
  }
}
function global:Invoke-WebRequest {
  [pscustomobject]@{ StatusCode = 200 }
}
$passed = $false
$message = ''
$result = @()
try {
  $result = @(& $env:GUANXIAN_STARTUP_TEST_SCRIPT -NoBuild)
  $passed = $true
} catch {
  $message = $_.Exception.Message
}
$report = [pscustomobject]@{
  Passed = $passed; Message = $message; Results = $result; Calls = $global:TestCalls.ToArray()
}
Write-Output ('E2E_TEST_RESULT:' + ($report | ConvertTo-Json -Depth 12 -Compress))
"""


class E2eStackStartupTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.pwsh = shutil.which("pwsh")
        if not cls.pwsh:
            raise RuntimeError("PowerShell 7 (pwsh) is required for startup script regression tests")

    def run_case(self, **overrides):
        case = {
            "ps_output": [SEED_ID], "ps_exit": 0,
            "inspect_output": json.dumps(COMPLETED), "inspect_exit": 0,
            "fail_command": "not-a-command",
        }
        case.update(overrides)
        env = dict(os.environ, GUANXIAN_STARTUP_TEST_CASE=json.dumps(case),
                   GUANXIAN_STARTUP_TEST_SCRIPT=str(ROOT / "tools/testing/Start-E2eStack.ps1"))
        completed = subprocess.run(
            [self.pwsh, "-NoLogo", "-NoProfile", "-NonInteractive", "-Command", HARNESS],
            capture_output=True, encoding="utf-8", env=env, timeout=30,
        )
        self.assertEqual(0, completed.returncode, completed.stderr)
        reports = [line.removeprefix("E2E_TEST_RESULT:") for line in completed.stdout.splitlines()
                   if line.startswith("E2E_TEST_RESULT:")]
        self.assertEqual(1, len(reports), completed.stdout + completed.stderr)
        return json.loads(reports[0])

    def assert_rejected(self, expected, **overrides):
        report = self.run_case(**overrides)
        self.assertFalse(report["Passed"], report)
        self.assertIn(expected, report["Message"])
        self.assertNotIn("null-valued expression", report["Message"])
        self.assertFalse(any(row.get("Seed") == "completed" for row in report["Results"]))
        # Diagnostics must include stopped containers, too.
        self.assertTrue(any("ps" in call and "--all" in call and call[-1] != "e2e-seed"
                            for call in report["Calls"]))
        return report

    def test_completed_oneshot_is_found_and_reported_successful(self):
        report = self.run_case()
        self.assertTrue(report["Passed"], report["Message"])
        self.assertEqual("completed", report["Results"][-1]["Seed"])
        self.assertTrue(any("ps" in call and "--all" in call and call[-1] == "e2e-seed"
                            for call in report["Calls"]))
        self.assertTrue(any("inspect" in call and "{{json .State}}" in call
                            for call in report["Calls"]))
        self.assertFalse(any("--build" in call for call in report["Calls"]))

    def test_empty_and_blank_ps_output_have_actionable_error(self):
        for output in ([], [None], ["", "   "]):
            with self.subTest(output=output):
                report = self.assert_rejected("was not created", ps_output=output)
                self.assertFalse(any("inspect" in call for call in report["Calls"]))

    def test_ps_failure_is_not_mistaken_for_missing_or_successful_container(self):
        for output in ([], [SEED_ID]):
            with self.subTest(output=output):
                report = self.assert_rejected("Failed to query", ps_exit=1, ps_output=output)
                self.assertFalse(any("inspect" in call for call in report["Calls"]))

    def test_multiple_containers_are_rejected(self):
        report = self.assert_rejected("exactly one", ps_output=[SEED_ID, "b" * 64])
        self.assertFalse(any("inspect" in call for call in report["Calls"]))

    def test_invalid_container_id_is_rejected(self):
        report = self.assert_rejected("invalid container ID", ps_output=["not-an-id"])
        self.assertFalse(any("inspect" in call for call in report["Calls"]))

    def test_whitespace_around_one_id_is_normalized(self):
        report = self.run_case(ps_output=["", "  " + SEED_ID + "  ", "  "])
        self.assertTrue(report["Passed"], report["Message"])
        self.assertTrue(any(call[0] == "inspect" and call[-1] == SEED_ID
                            for call in report["Calls"]))

    def test_inspect_failure_rejects_even_success_shaped_output(self):
        self.assert_rejected("Failed to inspect", inspect_exit=1)

    def test_empty_invalid_or_missing_state_is_rejected(self):
        for value in ("", "not-json", "null", "[]", "{}", '[{"Status":"exited"}]'):
            with self.subTest(value=value):
                self.assert_rejected("invalid or incomplete state", inspect_output=value)

    def test_non_completed_states_never_pass_with_zero_exit_code(self):
        for status in ("running", "created", "restarting", "paused", "dead", "removing"):
            with self.subTest(status=status):
                state = dict(COMPLETED, Status=status)
                self.assert_rejected("did not complete successfully", inspect_output=json.dumps(state))

    def test_unsuccessful_exit_is_rejected(self):
        for code in (1, 7, 137):
            with self.subTest(code=code):
                state = dict(COMPLETED, ExitCode=code)
                self.assert_rejected("did not complete successfully", inspect_output=json.dumps(state))

    def test_running_or_oom_flags_are_rejected(self):
        for flag in ("Running", "OOMKilled"):
            with self.subTest(flag=flag):
                state = dict(COMPLETED, **{flag: True})
                self.assert_rejected("did not complete successfully", inspect_output=json.dumps(state))

    def test_missing_and_wrongly_typed_fields_are_rejected(self):
        for field in COMPLETED:
            with self.subTest(missing=field):
                state = dict(COMPLETED)
                del state[field]
                self.assert_rejected("invalid or incomplete state", inspect_output=json.dumps(state))
        for field, value in (("ExitCode", None), ("ExitCode", "0"), ("ExitCode", False),
                             ("ExitCode", 0.5), ("Running", "false"), ("OOMKilled", "false")):
            with self.subTest(field=field, value=value):
                state = dict(COMPLETED, **{field: value})
                self.assert_rejected("invalid or incomplete state", inspect_output=json.dumps(state))

    def test_earlier_compose_failures_stop_before_seed_verification(self):
        for command in ("config", "up", "exec"):
            with self.subTest(command=command):
                report = self.assert_rejected("Docker Compose failed", fail_command=command)
                self.assertFalse(any("inspect" in call for call in report["Calls"]))


if __name__ == "__main__":
    unittest.main()
