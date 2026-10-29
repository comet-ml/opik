"""The Studio optimizer runner is started as a script, not as an installed module.

The backend image copies ``src/`` without installing ``opik_backend``, and starts the runner with
``python -u .../opik_backend/jobs/optimizer_runner.py``, so the runner must find its own package.
"""

import json
import os
import subprocess
import sys

from opik_backend.jobs.optimizer import OPTIMIZER_RUNNER_PATH

# Runs the runner as __main__ with every path that holds an installed opik_backend removed, as in the image.
WITHOUT_INSTALLED_PACKAGE = """
import os
import runpy
import sys

sys.path = [path for path in sys.path if not os.path.isdir(os.path.join(path or ".", "opik_backend"))]
sys.argv = [sys.argv[1]]
runpy.run_path(sys.argv[0], run_name="__main__")
"""


def test_runner_started_as_a_script_imports_its_own_package(tmp_path):
    env = {name: value for name, value in os.environ.items() if name != "PYTHONPATH"}

    actual = subprocess.run(
        [sys.executable, "-c", WITHOUT_INSTALLED_PACKAGE, OPTIMIZER_RUNNER_PATH],
        input="", capture_output=True, text=True, timeout=120, env=env, cwd=tmp_path)

    # An empty job is the only failure: the runner got past its imports and reports through its own error path.
    actual_reply = json.loads(actual.stdout.strip().splitlines()[-1])
    assert (actual.returncode, actual_reply["success"], actual_reply["error"]) == (
        1, False, "No input data received from stdin"), actual.stderr[-3000:]
