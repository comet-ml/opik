# mypy: disable-error-code=no-untyped-def
"""Run GepaOptimizer.optimize_prompt with gepa.optimize faked.

Lets a test assert on what we actually hand to gepa, rather than stopping at
the value our own resolver returns. ``test_gepa_stop_conditions`` grew its own
copy of this first; it is duplicated here rather than imported so that mypy
(which the pre-commit hook runs over changed files and everything they import)
does not pull that module's unrelated type errors into this PR's check.
"""

from typing import Any
from unittest.mock import MagicMock

from opik_optimizer import GepaOptimizer


def make_mock_gepa_result(**overrides: Any) -> MagicMock:
    mock_gepa_result = MagicMock()
    mock_gepa_result.history = []
    mock_gepa_result.pareto_front = []
    mock_gepa_result.total_metric_calls = 1
    for key, value in overrides.items():
        setattr(mock_gepa_result, key, value)
    return mock_gepa_result


def run_optimize_capturing_gepa_kwargs(
    monkeypatch,
    mock_optimization_context,
    simple_chat_prompt,
    mock_dataset,
    dataset_items,
    sample_metric,
    **optimize_kwargs: Any,
) -> dict[str, Any]:
    """Return the kwargs gepa.optimize was called with."""
    mock_optimization_context()

    optimizer = GepaOptimizer(model="gpt-4o-mini", verbose=0, seed=42)
    dataset = mock_dataset(dataset_items, name="test-dataset", dataset_id="dataset-123")
    monkeypatch.setattr(optimizer, "evaluate_prompt", lambda **kwargs: 0.5)

    captured: dict[str, Any] = {}

    def fake_optimize(**kwargs: Any) -> MagicMock:
        captured.update(kwargs)
        return make_mock_gepa_result()

    monkeypatch.setattr("gepa.optimize", fake_optimize)

    optimizer.optimize_prompt(
        prompt=simple_chat_prompt,
        dataset=dataset,
        metric=sample_metric,
        max_trials=2,
        n_samples=2,
        **optimize_kwargs,
    )
    return captured
