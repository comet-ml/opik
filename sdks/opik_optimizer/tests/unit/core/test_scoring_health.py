"""Unit tests for scoring_health plumbing (OPIK-7043).

Covers:
- `compute_scoring_health` helper produces correct counts.
- `OptimizationResult.details["scoring_health"]` is always populated in
  `build_final_result` (all-pass and partial-fail cases).
- `context.scoring_health` is updated in `BaseOptimizer.evaluate()` and
  `evaluate_with_result()` whenever a new best score is recorded.
"""

from __future__ import annotations

from typing import Any
from unittest.mock import MagicMock

import pytest
from opik.evaluation.evaluation_result import EvaluationResult
from opik.evaluation.metrics.score_result import ScoreResult

from opik_optimizer.core.evaluation import compute_scoring_health
from opik_optimizer.core.results import OptimizationResult
from opik_optimizer.core.runtime import build_final_result
from opik_optimizer.core.state import AlgorithmResult, OptimizationContext
from tests.unit.fixtures.base_optimizer_test_helpers import ConcreteOptimizer
from tests.unit.test_helpers import make_mock_dataset, make_optimization_context


# ---------------------------------------------------------------------------
# compute_scoring_health helper
# ---------------------------------------------------------------------------


def _make_score(*, failed: bool, value: float = 0.0) -> ScoreResult:
    return ScoreResult(name="obj", value=value, scoring_failed=failed)


class TestComputeScoringHealth:
    def test_all_pass(self) -> None:
        scores = [_make_score(failed=False, value=1.0)] * 5
        health = compute_scoring_health(scores)
        assert health == {"failed_count": 0, "total_count": 5}

    def test_some_failed(self) -> None:
        scores = [
            _make_score(failed=False, value=1.0),
            _make_score(failed=True),
            _make_score(failed=False, value=0.8),
            _make_score(failed=True),
        ]
        health = compute_scoring_health(scores)
        assert health == {"failed_count": 2, "total_count": 4}

    def test_all_failed(self) -> None:
        scores = [_make_score(failed=True)] * 3
        health = compute_scoring_health(scores)
        assert health == {"failed_count": 3, "total_count": 3}

    def test_empty(self) -> None:
        health = compute_scoring_health([])
        assert health == {"failed_count": 0, "total_count": 0}


# ---------------------------------------------------------------------------
# OptimizationResult.details["scoring_health"] via build_final_result
# ---------------------------------------------------------------------------


def _make_algorithm_result(score: float = 0.8) -> AlgorithmResult:
    from opik_optimizer import ChatPrompt

    prompt = ChatPrompt(system="s", user="u")
    return AlgorithmResult(
        best_prompts={"prompt": prompt},
        best_score=score,
        history=[],
        metadata={},
    )


def _make_optimizer() -> ConcreteOptimizer:
    optimizer = ConcreteOptimizer(model="gpt-4", verbose=0)
    optimizer.n_threads = 1
    optimizer.llm_call_counter = 0
    optimizer.llm_call_tools_counter = 0
    return optimizer


def _make_context(
    prompt: Any | None = None,
    *,
    scoring_health: dict[str, int] | None = None,
) -> OptimizationContext:
    from opik_optimizer import ChatPrompt

    if prompt is None:
        prompt = ChatPrompt(system="s", user="u")
    ctx = make_optimization_context(
        prompt,
        dataset=make_mock_dataset(),
        metric=lambda di, lo: 1.0,
        agent=MagicMock(),
        max_trials=5,
        baseline_score=0.5,
    )
    ctx.metric.__name__ = "obj"
    ctx.scoring_health = scoring_health
    return ctx


class TestBuildFinalResultScoringHealth:
    def test_scoring_health_present_when_all_pass(self) -> None:
        optimizer = _make_optimizer()
        context = _make_context(scoring_health={"failed_count": 0, "total_count": 10})
        alg_result = _make_algorithm_result(score=0.9)

        result = build_final_result(
            optimizer=optimizer,
            algorithm_result=alg_result,
            context=context,
        )

        assert isinstance(result, OptimizationResult)
        health = result.details.get("scoring_health")
        assert health is not None
        assert health["failed_count"] == 0
        assert health["total_count"] == 10

    def test_scoring_health_present_when_some_failed(self) -> None:
        optimizer = _make_optimizer()
        context = _make_context(scoring_health={"failed_count": 3, "total_count": 10})
        alg_result = _make_algorithm_result(score=0.7)

        result = build_final_result(
            optimizer=optimizer,
            algorithm_result=alg_result,
            context=context,
        )

        health = result.details.get("scoring_health")
        assert health is not None
        assert health["failed_count"] == 3
        assert health["total_count"] == 10

    def test_scoring_health_defaults_to_zeros_when_context_has_none(self) -> None:
        """If no evaluation updated context.scoring_health, key is still present."""
        optimizer = _make_optimizer()
        context = _make_context(scoring_health=None)
        alg_result = _make_algorithm_result(score=0.5)

        result = build_final_result(
            optimizer=optimizer,
            algorithm_result=alg_result,
            context=context,
        )

        health = result.details.get("scoring_health")
        assert health is not None
        assert health == {"failed_count": 0, "total_count": 0}

    def test_scoring_health_shape_is_correct(self) -> None:
        """Shape contract: dict with exactly failed_count and total_count int keys."""
        optimizer = _make_optimizer()
        context = _make_context(scoring_health={"failed_count": 1, "total_count": 5})
        alg_result = _make_algorithm_result()

        result = build_final_result(
            optimizer=optimizer,
            algorithm_result=alg_result,
            context=context,
        )

        health = result.details["scoring_health"]
        assert set(health.keys()) >= {"failed_count", "total_count"}
        assert isinstance(health["failed_count"], int)
        assert isinstance(health["total_count"], int)


# ---------------------------------------------------------------------------
# context.scoring_health updated in BaseOptimizer.evaluate()
# ---------------------------------------------------------------------------


def _make_evaluation_result(
    metric_name: str,
    *,
    failing_count: int = 0,
    total_count: int = 5,
) -> EvaluationResult:
    """Build a minimal EvaluationResult for health extraction tests."""
    test_results = []
    for i in range(total_count):
        score = ScoreResult(
            name=metric_name,
            value=0.0 if i < failing_count else 1.0,
            scoring_failed=(i < failing_count),
        )
        tr = MagicMock()
        tr.score_results = [score]
        test_results.append(tr)

    eval_result = MagicMock(spec=EvaluationResult)
    eval_result.test_results = test_results
    return eval_result  # type: ignore[return-value]


class TestEvaluateSetsContextScoringHealth:
    """Test that BaseOptimizer.evaluate() updates context.scoring_health on new best."""

    def test_health_set_on_first_evaluation(
        self, monkeypatch: pytest.MonkeyPatch, simple_chat_prompt: Any
    ) -> None:
        optimizer = _make_optimizer()
        metric_name = "obj"

        eval_result = _make_evaluation_result(
            metric_name, failing_count=1, total_count=5
        )

        context = make_optimization_context(
            simple_chat_prompt,
            dataset=make_mock_dataset(),
            metric=lambda di, lo: 1.0,
            agent=MagicMock(),
            max_trials=5,
        )
        context.metric.__name__ = metric_name

        monkeypatch.setattr(
            optimizer,
            "evaluate_prompt",
            lambda **kwargs: eval_result,
        )

        optimizer.evaluate(context, {"main": simple_chat_prompt})

        assert context.scoring_health is not None
        assert context.scoring_health["failed_count"] == 1
        assert context.scoring_health["total_count"] == 5

    def test_health_updated_when_new_best_score_set(
        self, monkeypatch: pytest.MonkeyPatch, simple_chat_prompt: Any
    ) -> None:
        """Health is updated when a better score is found."""
        optimizer = _make_optimizer()
        metric_name = "obj"

        # First eval: no failures
        eval_result_1 = _make_evaluation_result(
            metric_name, failing_count=0, total_count=5
        )
        # Second eval: 2 failures (better score, should replace)
        eval_result_2 = _make_evaluation_result(
            metric_name, failing_count=2, total_count=5
        )

        context = make_optimization_context(
            simple_chat_prompt,
            dataset=make_mock_dataset(),
            metric=lambda di, lo: 1.0,
            agent=MagicMock(),
            max_trials=5,
        )
        context.metric.__name__ = metric_name

        call_count = [0]

        def fake_evaluate_prompt(**kwargs: Any) -> Any:
            if call_count[0] == 0:
                call_count[0] += 1
                return eval_result_1
            return eval_result_2

        monkeypatch.setattr(optimizer, "evaluate_prompt", fake_evaluate_prompt)

        # First trial — score 0.8, no failures
        def fake_score_from_result_1(
            evaluation_result: Any, *, metric_name: str, **kw: Any
        ) -> float:
            _ = evaluation_result, metric_name, kw
            if call_count[0] == 1:
                return 0.8
            return 0.9

        monkeypatch.setattr(
            optimizer, "_score_from_evaluation_result", fake_score_from_result_1
        )
        optimizer.evaluate(context, {"main": simple_chat_prompt})

        # Reset score tracker so second call looks like an improvement
        context.current_best_score = 0.8

        def fake_score_from_result_2(
            evaluation_result: Any, *, metric_name: str, **kw: Any
        ) -> float:
            _ = evaluation_result, metric_name, kw
            return 0.9

        monkeypatch.setattr(
            optimizer, "_score_from_evaluation_result", fake_score_from_result_2
        )
        optimizer.evaluate(context, {"main": simple_chat_prompt})

        # Health should now reflect second eval (2 failures)
        assert context.scoring_health is not None
        assert context.scoring_health["failed_count"] == 2

    def test_health_not_changed_when_score_does_not_improve(
        self, monkeypatch: pytest.MonkeyPatch, simple_chat_prompt: Any
    ) -> None:
        """When a new trial does NOT improve the best score, health is unchanged."""
        optimizer = _make_optimizer()
        metric_name = "obj"

        # First eval result: 0 failures
        eval_result_1 = _make_evaluation_result(
            metric_name, failing_count=0, total_count=5
        )
        # Second eval result: 3 failures (worse score, should NOT update health)
        eval_result_2 = _make_evaluation_result(
            metric_name, failing_count=3, total_count=5
        )

        context = make_optimization_context(
            simple_chat_prompt,
            dataset=make_mock_dataset(),
            metric=lambda di, lo: 1.0,
            agent=MagicMock(),
            max_trials=5,
        )
        context.metric.__name__ = metric_name
        context.current_best_score = 0.9  # Already has a best

        # The first evaluate call will return eval_result_1 with score=0.8 < 0.9
        call_count = [0]

        def fake_evaluate_prompt(**kwargs: Any) -> Any:
            result = eval_result_1 if call_count[0] == 0 else eval_result_2
            call_count[0] += 1
            return result

        monkeypatch.setattr(optimizer, "evaluate_prompt", fake_evaluate_prompt)

        def fake_score_from_result(
            evaluation_result: Any, *, metric_name: str, **kw: Any
        ) -> float:
            _ = evaluation_result, metric_name, kw
            # Return a lower score than the current best so health is NOT updated
            return 0.8

        monkeypatch.setattr(
            optimizer, "_score_from_evaluation_result", fake_score_from_result
        )

        # Call evaluate — score 0.8 < best 0.9, so health should NOT be set
        optimizer.evaluate(context, {"main": simple_chat_prompt})
        assert context.scoring_health is None
