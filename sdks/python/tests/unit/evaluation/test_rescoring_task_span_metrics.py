"""Re-scoring must not drop the metrics the user configured (same class as OPIK-6925).

``test_silently_skipped_scores.py`` and ``test_error_tolerance.py`` state the rule
for ``evaluate()``: an evaluation the user asked for must never vanish without a
trace, and at the default tolerance a missing required score argument aborts the
run. ``score_test_cases`` — the engine entry point behind the public
``evaluate_experiment()`` re-scoring API — splits task-span metrics off and
discards that half of the split, so those metrics vanish there.
"""

import contextlib
from typing import Any, Dict, List
from unittest import mock

import pytest

import opik
from opik import exceptions, evaluation, url_helpers
from opik.api_objects import opik_client
from opik.evaluation import rest_operations, test_case
from opik.evaluation.engine import engine
from opik.evaluation.metrics import base_metric, score_result
from opik.evaluation.types import ErrorTolerance


class RequiresTaskSpan(base_metric.BaseMetric):
    """Scores the span collected while the LLM task ran — an input the public
    docstring of ``evaluate_experiment`` advertises as supported."""

    def __init__(self) -> None:
        super().__init__(name="requires_task_span", track=False)

    def score(self, task_span: Any, **ignored: Any) -> score_result.ScoreResult:
        return score_result.ScoreResult(name=self.name, value=1.0)


class AlwaysPasses(base_metric.BaseMetric):
    def __init__(self) -> None:
        super().__init__(name="always_passes", track=False)

    def score(self, output: str, **ignored: Any) -> score_result.ScoreResult:
        return score_result.ScoreResult(name=self.name, value=1.0)


def _test_cases(count: int = 1) -> List[test_case.TestCase]:
    return [
        test_case.TestCase(
            trace_id=f"trace-{index}",
            dataset_item_id=f"item-{index}",
            task_output={"output": "hello"},
            dataset_item_content={"input": "hi", "reference": "hello"},
        )
        for index in range(count)
    ]


def _score_test_cases(
    metrics: List[base_metric.BaseMetric],
    error_tolerance: ErrorTolerance,
) -> Dict[str, score_result.ScoreResult]:
    client = opik.Opik(project_name="test-project")
    evaluation_engine = engine.EvaluationEngine(
        client=client,
        project_name="test-project",
        workers=1,
        verbose=0,
        source="experiment",
        error_tolerance=error_tolerance,
    )
    test_results = evaluation_engine.score_test_cases(
        test_cases=_test_cases(),
        scoring_metrics=metrics,
        scoring_key_mapping=None,
    )
    return {result.name: result for result in test_results[0].score_results}


def _rescoring_lookup_patches():
    """Mock only the backend reads evaluate_experiment performs before scoring."""
    mock_experiment = mock.Mock(id="exp-id", name="exp-name", dataset_name="ds-name")
    return [
        mock.patch.object(
            rest_operations,
            "get_experiment_with_unique_name",
            return_value=mock_experiment,
        ),
        mock.patch.object(
            opik_client.Opik, "get_dataset", return_value=mock.Mock(id="dataset-id")
        ),
        mock.patch.object(
            rest_operations, "get_experiment_test_cases", return_value=_test_cases()
        ),
        mock.patch.object(
            rest_operations, "get_trace_project_name", return_value="test-project"
        ),
        mock.patch.object(
            url_helpers,
            "get_experiment_url_by_id",
            return_value="http://example.com/exp",
        ),
    ]


def test_score_test_cases__task_span_metric__default_tolerance__run_is_aborted(
    fake_backend,
):
    # METRIC_ERRORS is what `evaluate_experiment` runs with, and it is the level
    # at which a missing required score argument aborts (see types.py).
    with pytest.raises(exceptions.ScoreMethodMissingArguments) as exc_info:
        _score_test_cases(
            [AlwaysPasses(), RequiresTaskSpan()],
            error_tolerance=ErrorTolerance.METRIC_ERRORS,
        )

    assert exc_info.value.score_name == "requires_task_span"
    assert "task_span" in exc_info.value.missing_required_arguments


def test_score_test_cases__task_span_metric__tolerance_all__accumulated_as_failed_score(
    fake_backend,
):
    scores = _score_test_cases(
        [AlwaysPasses(), RequiresTaskSpan()],
        error_tolerance=ErrorTolerance.ALL_SCORING_ERRORS,
    )

    assert scores["always_passes"].scoring_failed is False
    unsatisfied = scores["requires_task_span"]
    assert unsatisfied.scoring_failed is True
    assert "task_span" in unsatisfied.reason
    assert unsatisfied.metadata["error_info"]["exception_type"] == (
        "ScoreMethodMissingArguments"
    )


class ScoresOutputWithOptionalSpan(base_metric.BaseMetric):
    """Takes the span when there is one, but can score without it. The docstring
    calls `task_span` optional for exactly this reason."""

    def __init__(self) -> None:
        super().__init__(name="optional_span_metric", track=False)

    def score(
        self,
        output: str,
        task_span: Any = None,
        **ignored: Any,
    ) -> score_result.ScoreResult:
        return score_result.ScoreResult(
            name=self.name, value=1.0 if task_span is None else 0.0
        )


def test_score_test_cases__optional_task_span_metric__still_scores(
    fake_backend,
):
    # Only metrics that *require* the span are unresolvable when re-scoring. A
    # metric that can do without one used to be dropped with the rest of them.
    scores = _score_test_cases(
        [ScoresOutputWithOptionalSpan()],
        error_tolerance=ErrorTolerance.METRIC_ERRORS,
    )

    assert scores["optional_span_metric"].value == 1.0
    assert scores["optional_span_metric"].scoring_failed is False


def test_evaluate_experiment__task_span_metric__default_tolerance__run_is_aborted(
    fake_backend,
):
    # The same contract seen through the public entry point users call.
    with contextlib.ExitStack() as stack:
        for patch in _rescoring_lookup_patches():
            stack.enter_context(patch)

        with pytest.raises(exceptions.ScoreMethodMissingArguments):
            evaluation.evaluate_experiment(
                experiment_name="exp-name",
                scoring_metrics=[RequiresTaskSpan()],
                verbose=0,
            )


def spans_were_named(
    dataset_item: Any, task_outputs: Any, task_span: Any
) -> score_result.ScoreResult:
    return score_result.ScoreResult(
        name="spans_were_named", value=float(task_span.name == "the-task")
    )


def test_evaluate_experiment__span_scoring_function__default_tolerance__run_is_aborted(
    fake_backend,
):
    # `scoring_functions` is the other documented way to ask for the span. The
    # wrapper defaults `task_span=None`, so without a report the scorer would be
    # called with the argument missing and raise from user code.
    with contextlib.ExitStack() as stack:
        for patch in _rescoring_lookup_patches():
            stack.enter_context(patch)

        with pytest.raises(exceptions.ScoreMethodMissingArguments) as exc_info:
            evaluation.evaluate_experiment(
                experiment_name="exp-name",
                scoring_metrics=[],
                scoring_functions=[spans_were_named],
                verbose=0,
            )

    assert exc_info.value.score_name == "spans_were_named"
    assert "task_span" in exc_info.value.missing_required_arguments


def span_is_optional(
    dataset_item: Any, task_outputs: Any, task_span: Any = None
) -> score_result.ScoreResult:
    return score_result.ScoreResult(
        name="span_is_optional", value=1.0 if task_span is None else 0.0
    )


def test_evaluate_experiment__optional_span_scoring_function__still_scores(
    fake_backend,
):
    # The docstring calls `task_span` optional, so a scorer that can do without it
    # must keep scoring here rather than be reported or dropped.
    with contextlib.ExitStack() as stack:
        for patch in _rescoring_lookup_patches():
            stack.enter_context(patch)

        result = evaluation.evaluate_experiment(
            experiment_name="exp-name",
            scoring_metrics=[],
            scoring_functions=[span_is_optional],
            verbose=0,
        )

    scored = {
        score.name: score for tr in result.test_results for score in tr.score_results
    }
    # The name alone would also match a failed score or a wrong branch value.
    assert set(scored) == {"span_is_optional"}
    assert scored["span_is_optional"].value == 1.0
    assert scored["span_is_optional"].scoring_failed is False


def test_score_test_cases__regular_metrics_only__scores_and_logs(fake_backend):
    # Guard the untouched path: re-scoring without span metrics still reports and
    # persists what it computed.
    with mock.patch.object(
        rest_operations,
        "log_test_result_feedback_scores",
        wraps=rest_operations.log_test_result_feedback_scores,
    ) as log_spy:
        scores = _score_test_cases(
            [AlwaysPasses()], error_tolerance=ErrorTolerance.METRIC_ERRORS
        )

    assert scores["always_passes"].scoring_failed is False
    logged = [call.kwargs["score_results"][0].name for call in log_spy.call_args_list]
    assert logged == ["always_passes"]


def catch_all_span(**task_span: Any) -> score_result.ScoreResult:
    return score_result.ScoreResult(name="catch_all_span", value=1.0)


def test_evaluate_experiment__catch_all_span_name__still_scores(fake_backend):
    with contextlib.ExitStack() as stack:
        for patch in _rescoring_lookup_patches():
            stack.enter_context(patch)

        result = evaluation.evaluate_experiment(
            experiment_name="exp-name",
            scoring_metrics=[],
            scoring_functions=[catch_all_span],
            verbose=0,
        )

    scored = {
        score.name: score for tr in result.test_results for score in tr.score_results
    }
    assert set(scored) == {"catch_all_span"}
    assert scored["catch_all_span"].value == 1.0
    assert scored["catch_all_span"].scoring_failed is False
