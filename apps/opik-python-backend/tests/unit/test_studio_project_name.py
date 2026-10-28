"""Tests that the Optimization Studio job carries project_name through to the optimizer.

When a Studio optimization is created against a specific project, the trial
experiments produced by the run must be attached to that same project so the
UI's project-scoped filter can find them. These tests cover the two seams that
this relies on: parsing the job message and forwarding project_name to
optimizer.optimize_prompt.
"""

import json
import os
import subprocess
import sys
import uuid
from unittest.mock import MagicMock

import pytest

import opik_backend

from opik_backend.studio.helpers import run_optimization
from opik_backend.studio.types import OptimizationJobContext


class TestOptimizationJobContextProjectName:
    """OptimizationJobContext.from_job_message must surface project_name."""

    def _base_message(self):
        return {
            "optimization_id": "opt-123",
            "workspace_id": "ws-1",
            "workspace_name": "default",
            "config": {"dataset_name": "ds"},
        }

    def test_project_name_when_present(self):
        message = self._base_message()
        message["project_name"] = "my-new-project"

        context = OptimizationJobContext.from_job_message(message)

        assert context.project_name == "my-new-project"

    def test_project_name_is_none_when_missing(self):
        context = OptimizationJobContext.from_job_message(self._base_message())

        assert context.project_name is None


class TestRunOptimizationForwardsProjectName:
    """run_optimization must forward project_name to optimizer.optimize_prompt."""

    def _call(self, project_name):
        optimizer = MagicMock()
        optimizer.optimize_prompt.return_value = MagicMock(
            score=1.0, initial_score=None
        )

        # A real message shape: run_optimization rejects a prompt with no
        # optimizable role, which is unrelated to what these tests cover.
        prompt = MagicMock()
        prompt.get_messages.return_value = [
            {"role": "system", "content": "You answer questions."},
            {"role": "user", "content": "Answer {question}"},
        ]

        run_optimization(
            optimizer=optimizer,
            optimization_id="opt-1",
            prompt=prompt,
            dataset=MagicMock(),
            metric_fn=lambda *_args, **_kwargs: 0.0,
            project_name=project_name,
        )

        assert optimizer.optimize_prompt.call_count == 1
        return optimizer.optimize_prompt.call_args

    def test_forwards_project_name_when_set(self):
        _args, kwargs = self._call("my-new-project")

        assert kwargs["project_name"] == "my-new-project"

    def test_passes_none_when_unset(self):
        _args, kwargs = self._call(None)

        # When unset, we pass None explicitly so the optimizer falls back to its
        # default ("Optimization") rather than picking up an unrelated env value.
        assert kwargs["project_name"] is None


# Runs the Studio runner's main() on one job, with the opik client, the metric and the optimizer replaced by
# recorders, and prints every call that takes a project as JSON. A separate process: importing the runner
# reconfigures logging and the environment for the whole process.
JOB_PATH_SCRIPT = """
import io
import json
import os
import sys
import types
from unittest.mock import MagicMock

import opik
from opik_backend.studio import metrics

calls = []


class RecordingDataset:
    name = sys.argv[2]

    def get_items(self, nb_samples=None):
        return [{"id": "1", "text": "great", "label": "positive"}, {"id": "2", "text": "awful", "label": "negative"}]


class RecordingClient:
    def __init__(self, **kwargs):
        calls.append({"call": "Opik", **kwargs})
        self.rest_client = MagicMock()

    def get_dataset(self, name, **kwargs):
        calls.append({"call": "get_dataset", "name": name, **kwargs})
        return RecordingDataset()


class RecordingEquals(metrics.Equals):
    def __init__(self, **kwargs):
        calls.append({"call": "Equals", "project_name": kwargs.get("project_name")})
        super().__init__(**kwargs)


class RecordingOptimizer:
    def optimize_prompt(self, **kwargs):
        calls.append({
            "call": "optimize_prompt",
            "project_name": kwargs["project_name"],
            "OPIK_PROJECT_NAME": os.environ.get("OPIK_PROJECT_NAME"),
        })
        return types.SimpleNamespace(score=1.0, initial_score=0.5, prompt=None)


opik.Opik = RecordingClient
metrics.Equals = RecordingEquals
sys.stdin = io.StringIO(json.dumps({"data": {
    "optimization_id": "0190babc-62a0-71d2-832a-0feffa4676eb",
    "workspace_id": "workspace",
    "workspace_name": "workspace",
    "project_name": sys.argv[1],
    "config": {
        "dataset_name": sys.argv[2],
        "prompt": {"messages": [{"role": "user", "content": "Classify: {{text}}"}]},
        "llm_model": {"model": "gpt-5-nano", "parameters": {}},
        "evaluation": {"metrics": [{"type": "equals", "parameters": {"reference_key": "label"}}]},
        "optimizer": {"type": "gepa", "parameters": {}},
    },
}}))

from opik_backend.jobs import optimizer_runner
from opik_optimizer import ChatPrompt


def build_optimizer_and_prompt(config):
    return RecordingOptimizer(), ChatPrompt(messages=config.prompt_messages, model="gpt-5-nano")


optimizer_runner.build_optimizer_and_prompt = build_optimizer_and_prompt
optimizer_runner.main()
print("CALLS " + json.dumps(calls), file=sys.stderr)
"""


def test_job_uses_its_project_for_every_call_that_takes_one(tmp_path):
    project_name = f"studio-project-{uuid.uuid4().hex[:8]}"
    dataset_name = f"studio-dataset-{uuid.uuid4().hex[:8]}"
    script = tmp_path / "run_job.py"
    script.write_text(JOB_PATH_SCRIPT)
    src = os.path.dirname(os.path.dirname(os.path.abspath(opik_backend.__file__)))
    env = {key: value for key, value in os.environ.items() if key != "OPIK_PROJECT_NAME"}
    env.update(PYTHON_COLORS="0", PYTHON_CODE_EXECUTOR_PROCESS_NETWORK_ISOLATION="false",
               PYTHONPATH=os.pathsep.join(filter(None, [src, os.environ.get("PYTHONPATH")])))

    run = subprocess.run([sys.executable, str(script), project_name, dataset_name],
                         capture_output=True, text=True, timeout=180, env=env)

    calls_line = [line for line in run.stderr.splitlines() if line.startswith("CALLS ")]
    assert calls_line, run.stderr[-3000:]
    assert json.loads(calls_line[-1][len("CALLS "):]) == [
        {"call": "Opik", "project_name": project_name},
        {"call": "get_dataset", "name": dataset_name, "project_name": project_name},
        {"call": "Equals", "project_name": project_name},
        {"call": "optimize_prompt", "project_name": project_name, "OPIK_PROJECT_NAME": project_name},
    ]
