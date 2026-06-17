"""End-to-end regression test for the Optimization Studio **CODE metric**.

The CODE metric runs user-supplied Python through the executor infrastructure
(``PYTHON_CODE_EXECUTOR_STRATEGY``) inside the optimization subprocess. This
test exercises that path through the **real entrypoint** (``process_optimizer_job``),
with the provider key stored in the workspace and resolved server-side via the
gateway — the same pattern as ``test_studio_optimization`` — so no provider key
is ever handed to the optimizer.

Given the sentiment dataset and a one-message prompt, it asserts the run is
healthy (baseline + optimized prompt + no regression), which only happens if the
user's ``BaseMetric`` subclass executed and produced scores end-to-end.

Bound the run via ``OPTIMIZER_MAX_TRIALS`` (set in CI) so it stays short.
"""

from typing import Any

import pytest

import opik

from studio_helpers import assert_optimization_healthy, run_studio_job

pytestmark = pytest.mark.e2e

# Bare model id (the runner adds the "openai/" gateway prefix); resolves to the
# workspace Anthropic key server-side.
_TASK_MODEL = "claude-haiku-4-5-20251001"

# A user-authored BaseMetric: scores 1.0 when the gold label appears in the
# model's output. `kwargs` carries the dataset item fields (here, `label`).
_CODE_METRIC = '''
from opik.evaluation.metrics import BaseMetric
from opik.evaluation.metrics.score_result import ScoreResult


class LabelMatch(BaseMetric):
    def __init__(self, name: str = "label_match"):
        super().__init__(name=name)

    def score(self, output: str, **kwargs) -> ScoreResult:
        label = str(kwargs.get("label", "")).strip().lower()
        matched = bool(label) and label in (output or "").lower()
        return ScoreResult(
            name=self.name,
            value=1.0 if matched else 0.0,
            reason=f"label {label!r} {'found' if matched else 'missing'}",
        )
'''


def _studio_config(model: str, dataset_name: str) -> dict[str, Any]:
    return {
        "dataset_name": dataset_name,
        "prompt": {
            "messages": [
                {
                    "role": "user",
                    "content": 'Classify the sentiment of this movie review as '
                    'exactly "positive" or "negative": {{text}}',
                }
            ]
        },
        "llm_model": {"model": model, "parameters": {}},
        "evaluation": {"metrics": [{"type": "code", "parameters": {"code": _CODE_METRIC}}]},
        "optimizer": {"type": "gepa", "parameters": {"seed": 42}},
    }


def test_code_metric_optimization_runs_end_to_end(
    opik_client: opik.Opik,
    anthropic_workspace_key: None,
    project_name: str,
    seeded_dataset: opik.Dataset,
) -> None:
    studio_config = _studio_config(_TASK_MODEL, seeded_dataset.name)

    result = run_studio_job(opik_client, project_name, seeded_dataset.name, studio_config)

    assert_optimization_healthy(result)
