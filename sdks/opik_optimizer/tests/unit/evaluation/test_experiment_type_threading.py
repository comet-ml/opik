# mypy: disable-error-code=no-untyped-def

from typing import Any
from unittest.mock import MagicMock

from opik_optimizer.core import evaluation as task_evaluator
from tests.unit.fixtures.builders import make_mock_dataset, make_simple_metric


def _capture_trial_call(monkeypatch) -> dict[str, Any]:
    captured: dict[str, Any] = {}

    def fake_trial(**kwargs: Any) -> MagicMock:
        captured.update(kwargs)
        eval_result = MagicMock()
        eval_result.test_results = []
        return eval_result

    monkeypatch.setattr(
        task_evaluator.opik_evaluator, "evaluate_optimization_trial", fake_trial
    )
    return captured


def _run_evaluate_with_result(**overrides: Any) -> Any:
    kwargs: dict[str, Any] = {
        "dataset": make_mock_dataset([{"id": "1", "question": "q", "answer": "a"}]),
        "evaluated_task": lambda item: {"llm_output": "x"},
        "metric": make_simple_metric(),
        "num_threads": 1,
        "optimization_id": "opt-1",
        "dataset_item_ids": ["1"],
    }
    kwargs.update(overrides)
    return task_evaluator.evaluate_with_result(**kwargs)


class TestExperimentTypeThreading:
    def test_experiment_type_reaches_optimization_trial(self, monkeypatch) -> None:
        captured = _capture_trial_call(monkeypatch)
        monkeypatch.setattr(
            task_evaluator,
            "_EVALUATE_OPTIMIZATION_TRIAL_ACCEPTS_EXPERIMENT_TYPE",
            True,
        )

        _run_evaluate_with_result(experiment_type="mini-batch")

        assert captured["experiment_type"] == "mini-batch"

    def test_default_omits_experiment_type(self, monkeypatch) -> None:
        """Other optimizers pass nothing; the kwarg must not be sent at all so
        behavior stays bit-identical."""
        captured = _capture_trial_call(monkeypatch)

        _run_evaluate_with_result()

        assert "experiment_type" not in captured

    def test_old_sdk_without_param_falls_back_gracefully(self, monkeypatch) -> None:
        """Against an opik SDK whose evaluate_optimization_trial lacks the
        parameter, we log and record the legacy 'trial' type instead of crashing."""
        captured = _capture_trial_call(monkeypatch)
        monkeypatch.setattr(
            task_evaluator,
            "_EVALUATE_OPTIMIZATION_TRIAL_ACCEPTS_EXPERIMENT_TYPE",
            False,
        )

        _run_evaluate_with_result(experiment_type="mini-batch")

        assert "experiment_type" not in captured

    def test_installed_sdk_supports_experiment_type(self) -> None:
        """The repo's own opik SDK must expose the parameter (guards the
        feature-detection constant against regressions)."""
        assert task_evaluator._EVALUATE_OPTIMIZATION_TRIAL_ACCEPTS_EXPERIMENT_TYPE
