"""Exercise the checked-in E2E runner without Docker, npm, or Allure services.

Install: uv pip install --require-hashes --only-binary :all: -r tests/unit/requirements-workflow.txt
Run: python tests/unit/test_e2e_allure_workflow.py
Requires Bash on PATH (as on the workflow's Ubuntu runner).
"""

import os
from pathlib import Path
import subprocess
import shutil
import unittest
from unittest.mock import patch

import yaml


class AllureWorkflowTest(unittest.TestCase):
    @patch.dict(os.environ, {"WORKFLOW_TEST_PARENT_SECRET": "test-only-sentinel"})
    def test_credential_routes_preserve_test_failures(self):
        root = Path(__file__).resolve().parents[2]
        workflow = yaml.safe_load(
            (root / ".github/workflows/end2end_suites_v2.yml").read_text()
        )
        steps = workflow["jobs"]["run_suite"]["steps"]
        install = next(s for s in steps if s.get("name") == "Install allurectl")
        self.assertEqual(install["if"], "${{ env.ALLURE_TOKEN != '' }}")
        runner = next(s for s in steps if s.get("name") == "Run v2 E2E suite")
        self.assertLess(steps.index(install), steps.index(runner))
        # Substitute only the runner-provided workspace expression. Everything
        # else is the actual checked-in shell block, not a copy of its logic.
        body = runner["run"].replace("${{ github.workspace }}", "/mock-workspace")
        self.assertNotIn("${{", body)
        stubs = r'''
npm() { [[ -z "${WORKFLOW_TEST_PARENT_SECRET:-}" ]] || return 98; printf 'npm:%s\n' "$*"; return "$TEST_EXIT"; }
allurectl() {
  printf 'allure:%s\n' "$*"
  if [[ "$1" != watch || "$2" != -- ]]; then return 99; fi
  shift 2
  "$@"
}
'''
        for token in (None, "", "test-placeholder"):
            for tier in ("t1", "t2", "t3"):
                for code in (0, 7):
                    with self.subTest(token=token, tier=tier, exit=code):
                        # Keep only process lookup/platform essentials; never inherit
                        # credentials or Bash startup hooks from the caller.
                        env = {key: os.environ[key] for key in ("PATH", "SYSTEMROOT")
                               if key in os.environ}
                        env.update(TIER=tier, TEST_EXIT=str(code))
                        if token is not None:
                            env["ALLURE_TOKEN"] = token
                        result = subprocess.run(
                            [shutil.which("bash") or "bash", "--noprofile", "--norc", "-eo", "pipefail",
                             "-c", stubs + body],
                            env=env, capture_output=True, text=True, timeout=10,
                        )
                        self.assertEqual(result.returncode, code, result.stderr)
                        calls = [line for line in result.stdout.splitlines()
                                 if line.startswith(("npm:", "allure:"))]
                        expected = [f"npm:run test:{tier}"]
                        if token:
                            expected.insert(0, f"allure:watch -- npm run test:{tier}")
                        self.assertEqual(calls, expected)


if __name__ == "__main__":
    unittest.main()
