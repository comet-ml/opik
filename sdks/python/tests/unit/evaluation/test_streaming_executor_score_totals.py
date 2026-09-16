"""``StreamingExecutor._on_future_done`` running-score accounting (#8134).

The aggregate tests cover the final numbers; the multi-worker tests dispatch
through the callback without asserting what it accumulated. This is the path
that paints the live average in the progress bar, so a regression here shows
the user a number that no later report reproduces.
"""

from concurrent import futures
from unittest import mock

import pytest

from opik.evaluation import test_case, test_result
from opik.evaluation.engine import evaluation_tasks_executor
from opik.evaluation.metrics import score_result


def _result(*scores: score_result.ScoreResult) -> test_result.TestResult:
    return test_result.TestResult(
        test_case=test_case.TestCase(
            trace_id="t", dataset_item_id="d", task_output={}, dataset_item_content={}
        ),
        score_results=list(scores),
        trial_id=0,
    )


def _completed_future(result: test_result.TestResult) -> futures.Future:
    future: futures.Future = futures.Future()
    future.set_result(result)
    return future


def _executor() -> evaluation_tasks_executor.StreamingExecutor:
    executor = evaluation_tasks_executor.StreamingExecutor(
        workers=1,
        verbose=0,
        client=None,
        total=2,
    )
    # __enter__ builds the real bar; the callback only needs these two methods.
    executor._progress_bar = mock.Mock()
    return executor


def test_failed_score_updates_the_running_average_and_the_postfix() -> None:
    executor = _executor()

    executor._on_future_done(
        _completed_future(_result(score_result.ScoreResult(name="h", value=1.0)))
    )
    executor._on_future_done(
        _completed_future(
            _result(
                score_result.ScoreResult(
                    name="h", value=0.0, reason="boom", scoring_failed=True
                )
            )
        )
    )

    assert executor._score_counts["h"] == 2
    assert executor._score_totals["h"] == 1.0
    postfix = executor._progress_bar.set_postfix.call_args.args[0]
    assert postfix == {"h": "0.5000"}


@pytest.mark.parametrize("leftover", [float("nan"), float("inf")])
def test_a_non_finite_value_left_by_a_failure_counts_as_the_recorded_zero(
    leftover: float,
) -> None:
    """A metric builds its own ``ScoreResult``, so a failure can carry anything.

    Summing it unconditionally would print ``nan`` as the live average for the
    rest of the run, while the final report and the console table showed a
    different number.
    """
    executor = _executor()

    executor._on_future_done(
        _completed_future(_result(score_result.ScoreResult(name="h", value=1.0)))
    )
    executor._on_future_done(
        _completed_future(
            _result(
                score_result.ScoreResult(
                    name="h", value=leftover, reason="boom", scoring_failed=True
                )
            )
        )
    )

    assert executor._score_totals["h"] == 1.0
    postfix = executor._progress_bar.set_postfix.call_args.args[0]
    assert postfix == {"h": "0.5000"}


def test_successful_scores_are_unaffected() -> None:
    """Control: the callback still sums a passing score verbatim."""
    executor = _executor()

    executor._on_future_done(
        _completed_future(_result(score_result.ScoreResult(name="h", value=0.25)))
    )
    executor._on_future_done(
        _completed_future(_result(score_result.ScoreResult(name="h", value=0.75)))
    )

    assert executor._score_totals["h"] == 1.0
    assert executor._score_counts["h"] == 2
    postfix = executor._progress_bar.set_postfix.call_args.args[0]
    assert postfix == {"h": "0.5000"}
