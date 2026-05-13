"""Tests that the Optimization Studio job carries project_name through to the optimizer.

When a Studio optimization is created against a specific project, the trial
experiments produced by the run must be attached to that same project so the
UI's project-scoped filter can find them. These tests cover the two seams that
this relies on: parsing the job message and forwarding project_name to
optimizer.optimize_prompt.
"""

from unittest.mock import MagicMock

import pytest

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

        run_optimization(
            optimizer=optimizer,
            optimization_id="opt-1",
            prompt=MagicMock(),
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
