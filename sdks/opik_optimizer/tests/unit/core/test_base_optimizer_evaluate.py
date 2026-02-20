"""Unit tests for BaseOptimizer evaluation-related behaviors."""

# mypy: disable-error-code=no-untyped-def

from __future__ import annotations

from decimal import Decimal
from typing import Any
from unittest.mock import MagicMock

import pytest

from opik.evaluation.metrics import base_metric
from opik.evaluation import evaluation_result as opik_evaluation_result
from opik.evaluation import test_case as opik_test_case
from opik.evaluation import test_result as opik_test_result
from opik_optimizer.base_optimizer import BaseOptimizer
from opik_optimizer.constants import MAX_EVAL_THREADS, MIN_EVAL_THREADS
from opik_optimizer.api_objects import chat_prompt
from opik.evaluation.metrics import score_result
from tests.unit.fixtures.base_optimizer_test_helpers import (
    ConcreteOptimizer,
    _DisplaySpy,
)
from tests.unit.test_helpers import (
    make_candidate_agent,
    make_mock_dataset,
    make_optimization_context,
)
from collections.abc import Callable
from tests.unit.fixtures import user_message


@pytest.mark.parametrize(
    "selection_policy,expected_output,logprobs,metric_func",
    [
        (
            None,  # Default: best candidate
            "good",
            None,
            lambda _di, lo: 1.0 if lo == "good" else 0.0,
        ),
        ("first", "bad", None, lambda _di, _lo: 1.0),
        ("concat", "bad\n\ngood", None, lambda _di, _lo: 1.0),
        ("max_logprob", "good", [0.2, 0.9], lambda _di, _lo: 1.0),
    ],
)
def test_evaluate_prompt_selection_policies(
    monkeypatch: pytest.MonkeyPatch,
    selection_policy: str | None,
    expected_output: str,
    logprobs: list[float] | None,
    metric_func: Callable,
) -> None:
    """Test different selection policies for candidate evaluation."""
    optimizer = ConcreteOptimizer(model="gpt-4")
    agent = make_candidate_agent(candidates=["bad", "good"], logprobs=logprobs)

    model_params: dict[str, Any] = {"n": 2}
    if selection_policy:
        model_params["selection_policy"] = selection_policy

    prompt = chat_prompt.ChatPrompt(
        name="p",
        messages=[user_message("{input}")],
        model_parameters=model_params,
    )

    def fake_evaluate_with_result(
        dataset: Any,
        evaluated_task: Callable[[dict[str, Any]], dict[str, Any]],
        metric: Any,
        num_threads: int,
        optimization_id: str | None = None,
        dataset_item_ids: list[str] | None = None,
        project_name: str | None = None,
        n_samples: int | float | str | None = None,
        experiment_config: dict[str, Any] | None = None,
        verbose: int = 1,
        use_evaluate_on_dict_items: bool = False,
    ) -> tuple[float, opik_evaluation_result.EvaluationResultOnDictItems]:
        _ = (
            dataset,
            metric,
            num_threads,
            optimization_id,
            dataset_item_ids,
            project_name,
            n_samples,
            experiment_config,
            verbose,
            use_evaluate_on_dict_items,
        )
        output = evaluated_task({"id": "1", "input": "x"})
        assert output["llm_output"] == expected_output
        test_result = opik_test_result.TestResult(
            test_case=opik_test_case.TestCase(
                trace_id="trace-1",
                dataset_item_id="item-1",
                task_output=output,
                dataset_item_content={"id": "1", "input": "x"},
            ),
            score_results=[
                score_result.ScoreResult(name=metric_func.__name__, value=1.0)
            ],
            trial_id=0,
        )
        return (
            1.0,
            opik_evaluation_result.EvaluationResultOnDictItems(
                test_results=[test_result]
            ),
        )

    monkeypatch.setattr(
        "opik_optimizer.base_optimizer.task_evaluator.evaluate_with_result",
        fake_evaluate_with_result,
    )

    dataset = make_mock_dataset([{"id": "1", "input": "x"}])

    score = optimizer.evaluate_prompt(
        prompt=prompt,
        dataset=dataset,
        metric=metric_func,
        agent=agent,
        n_threads=1,
        verbose=0,
    )

    assert score == 1.0


def test_evaluate_forwards_configured_n_threads(
    monkeypatch: pytest.MonkeyPatch, simple_chat_prompt
) -> None:
    """Candidate evaluations should honor the optimizer's configured n_threads."""
    optimizer = ConcreteOptimizer(model="gpt-4", verbose=0)
    optimizer.n_threads = 1
    dataset = make_mock_dataset()
    metric = MagicMock()
    agent = MagicMock()

    context = make_optimization_context(
        simple_chat_prompt,
        dataset=dataset,
        metric=metric,
        agent=agent,
        max_trials=3,
    )

    captured_call: dict[str, Any] = {}

    def fake_evaluate_prompt(
        self,
        *,
        prompt,
        dataset,
        metric,
        agent,
        experiment_config,
        n_samples,
        verbose,
        n_threads=None,
        **kwargs,
    ):
        _ = (
            prompt,
            dataset,
            metric,
            agent,
            experiment_config,
            n_samples,
            verbose,
            kwargs,
        )
        captured_call["n_threads"] = n_threads
        return 0.5

    monkeypatch.setattr(ConcreteOptimizer, "evaluate_prompt", fake_evaluate_prompt)

    score = optimizer.evaluate(context, {"main": simple_chat_prompt})

    assert score == 0.5
    assert captured_call["n_threads"] == optimizer.n_threads


def test_normalize_n_threads_clamps_bounds(
    monkeypatch: pytest.MonkeyPatch, simple_chat_prompt
) -> None:
    optimizer = ConcreteOptimizer(model="gpt-4", verbose=0)
    optimizer.n_threads = 99999
    dataset = make_mock_dataset()
    metric = MagicMock()
    agent = MagicMock()

    context = make_optimization_context(
        simple_chat_prompt,
        dataset=dataset,
        metric=metric,
        agent=agent,
        max_trials=3,
    )

    captured: dict[str, Any] = {}

    def fake_eval_prompt(self, **kwargs):  # type: ignore[no-untyped-def]
        captured.update(kwargs)
        return 0.1

    monkeypatch.setattr(ConcreteOptimizer, "evaluate_prompt", fake_eval_prompt)

    optimizer.evaluate(context, {"main": simple_chat_prompt})
    assert MIN_EVAL_THREADS <= captured.get("n_threads", 0) <= MAX_EVAL_THREADS


def test_evaluate_coerces_infinite_scores(
    monkeypatch: pytest.MonkeyPatch, simple_chat_prompt
) -> None:
    """Metrics returning non-builtin numerics (e.g., Decimal('Infinity')) are coerced safely."""
    optimizer = ConcreteOptimizer(model="gpt-4", verbose=0)
    dataset = make_mock_dataset()
    metric = MagicMock()
    agent = MagicMock()

    context = make_optimization_context(
        simple_chat_prompt,
        dataset=dataset,
        metric=metric,
        agent=agent,
        max_trials=3,
        current_best_score=float("inf"),
    )

    def fake_evaluate_prompt(self, **_kwargs):  # type: ignore[no-untyped-def]
        return Decimal("Infinity")

    monkeypatch.setattr(ConcreteOptimizer, "evaluate_prompt", fake_evaluate_prompt)

    score = optimizer.evaluate(context, {"main": simple_chat_prompt})

    assert score == float("inf")
    assert context.current_best_score == float("inf")
    assert context.trials_completed == 1


def test_coerce_score_rejects_nan() -> None:
    """NaN scores should raise a ValueError."""
    with pytest.raises(ValueError, match="Score cannot be NaN"):
        BaseOptimizer._coerce_score(float("nan"))


def test_coerce_score_rejects_non_numeric() -> None:
    """Non-numeric scores should raise a TypeError."""
    with pytest.raises(TypeError, match="Score must be convertible to float"):
        BaseOptimizer._coerce_score(object())


def test_reporter_helpers_set_and_clear() -> None:
    optimizer = ConcreteOptimizer(model="gpt-4")
    reporter = object()
    optimizer._set_reporter(reporter)
    assert optimizer._reporter is reporter
    optimizer._clear_reporter()
    assert optimizer._reporter is None


def test_on_trial_handles_non_finite_scores(
    monkeypatch: pytest.MonkeyPatch, simple_chat_prompt
) -> None:
    """on_trial should not crash when scores are non-finite."""
    optimizer = ConcreteOptimizer(model="gpt-4", verbose=1)
    dataset = make_mock_dataset()
    metric = MagicMock()
    agent = MagicMock()

    context = make_optimization_context(
        simple_chat_prompt,
        dataset=dataset,
        metric=metric,
        agent=agent,
        max_trials=5,
    )
    context.current_best_score = float("inf")
    context.trials_completed = 1
    captured: dict[str, Any] = {}

    def fake_display_evaluation_progress(**kwargs):
        captured.update(kwargs)

    monkeypatch.setattr(
        "opik_optimizer.utils.display.terminal.display_evaluation_progress",
        fake_display_evaluation_progress,
    )

    optimizer.on_trial(context, {"main": simple_chat_prompt}, float("inf"))

    assert captured["style"] == "yellow"
    assert captured["score_text"] == "non-finite score"


def test_evaluate_prompt_on_dict_items_strips_ids(
    monkeypatch: pytest.MonkeyPatch, simple_chat_prompt
) -> None:
    optimizer = ConcreteOptimizer(model="gpt-4")
    dataset = make_mock_dataset(
        [
            {"id": "item-1", "input": "a"},
            {"id": "item-2", "input": "b"},
        ]
    )
    metric = MagicMock()
    metric.__name__ = "custom_metric"

    captured_items: list[list[dict[str, Any]]] = []

    class DummyResult:
        def __init__(self) -> None:
            score = score_result.ScoreResult(
                name="custom_metric",
                value=1.0,
                reason="ok",
            )
            test_result = MagicMock()
            test_result.score_results = [score]
            self.test_results = [test_result]

    def fake_evaluate_on_dict_items(*, items: list[dict[str, Any]], **kwargs: Any):
        _ = kwargs
        captured_items.append(items)
        return DummyResult()

    monkeypatch.setattr(
        "opik_optimizer.core.evaluation._evaluate_on_dict_items",
        fake_evaluate_on_dict_items,
    )

    result = optimizer.evaluate_prompt(
        prompt=simple_chat_prompt,
        dataset=dataset,
        metric=metric,
        agent=MagicMock(),
        n_threads=1,
        verbose=0,
        use_evaluate_on_dict_items=True,
        return_evaluation_result=True,
    )

    assert isinstance(result, DummyResult)
    assert captured_items
    assert all("id" not in item for item in captured_items[0])


def test_evaluate_prompt_on_dict_items_forwards_experiment_config(
    monkeypatch: pytest.MonkeyPatch, simple_chat_prompt
) -> None:
    optimizer = ConcreteOptimizer(model="gpt-4")
    dataset = make_mock_dataset(
        [{"id": "item-1", "input": "a"}],
    )
    metric = MagicMock()
    metric.__name__ = "custom_metric"
    captured: dict[str, Any] = {}

    class DummyResult:
        def __init__(self) -> None:
            score = score_result.ScoreResult(
                name="custom_metric",
                value=0.5,
                reason="ok",
            )
            test_result = MagicMock()
            test_result.score_results = [score]
            self.test_results = [test_result]

    def fake_evaluate_on_dict_items(
        *,
        items: list[dict[str, Any]],
        task: Callable[[dict[str, Any]], dict[str, Any]],
        scoring_metrics: list[base_metric.BaseMetric],
        project_name: str | None,
        verbose: int,
        scoring_threads: int,
        experiment_config: dict[str, Any] | None = None,
    ) -> DummyResult:
        _ = items, task, scoring_metrics, project_name, verbose, scoring_threads
        captured["experiment_config"] = experiment_config
        return DummyResult()

    monkeypatch.setattr(
        "opik_optimizer.core.evaluation._opik_evaluate_on_dict_items",
        fake_evaluate_on_dict_items,
    )
    monkeypatch.setattr(
        "opik_optimizer.core.evaluation._EVALUATE_ON_DICT_ITEMS_ACCEPTS_EXPERIMENT_CONFIG",
        True,
    )

    result = optimizer.evaluate_prompt(
        prompt=simple_chat_prompt,
        dataset=dataset,
        metric=metric,
        agent=MagicMock(),
        n_threads=1,
        verbose=0,
        experiment_config={"foo": "bar"},
        use_evaluate_on_dict_items=True,
        return_evaluation_result=True,
    )

    assert isinstance(result, DummyResult)
    assert captured["experiment_config"]["foo"] == "bar"


def test_optimize_prompt_uses_injected_display(
    monkeypatch: pytest.MonkeyPatch, simple_chat_prompt
) -> None:
    spy = _DisplaySpy()
    optimizer = ConcreteOptimizer(model="gpt-4", display=spy)

    mock_dataset = MagicMock()
    mock_metric = MagicMock(__name__="metric")

    def fake_setup(*_args, **_kwargs):
        return make_optimization_context(
            simple_chat_prompt,
            dataset=mock_dataset,
            metric=mock_metric,
            agent=MagicMock(),
            max_trials=1,
        )

    monkeypatch.setattr(optimizer, "_setup_optimization", fake_setup)
    monkeypatch.setattr(optimizer, "_calculate_baseline", lambda _ctx: 0.1)
    monkeypatch.setattr(optimizer, "_should_skip_optimization", lambda _score: True)
    monkeypatch.setattr(optimizer, "_build_early_result", lambda **_kwargs: MagicMock())
    monkeypatch.setattr(optimizer, "_finalize_optimization", lambda *_a, **_k: None)

    BaseOptimizer.optimize_prompt(
        optimizer,
        prompt=simple_chat_prompt,
        dataset=mock_dataset,
        metric=mock_metric,
        max_trials=1,
    )

    assert spy.header_calls, "Injected display handler should be used"


def test_evaluate_prompt_records_no_result_report(
    monkeypatch: pytest.MonkeyPatch, simple_chat_prompt
) -> None:
    optimizer = ConcreteOptimizer(model="gpt-4")
    dataset = make_mock_dataset([{"id": "1", "input": "x"}])
    metric = MagicMock()
    metric.__name__ = "metric_fn"
    agent = MagicMock()

    def fake_evaluate_with_result(**kwargs: Any):
        _ = kwargs
        return 0.0, None

    monkeypatch.setattr(
        "opik_optimizer.base_optimizer.task_evaluator.evaluate_with_result",
        fake_evaluate_with_result,
    )

    score = optimizer.evaluate_prompt(
        prompt=simple_chat_prompt,
        dataset=dataset,
        metric=metric,
        agent=agent,
        n_threads=1,
        verbose=0,
    )

    assert score == 0.0
    assert optimizer._last_evaluation_report is not None
    assert optimizer._last_evaluation_report["objective_metric"] == "metric_fn"
    assert optimizer._last_evaluation_report["evaluated_items"] == 0
    assert optimizer._last_evaluation_report["objective_scores"] == 0
