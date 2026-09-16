"""#8134 defect 1: failed metric scores must not be silently dropped.

A metric exception produces ``ScoreResult(0.0, scoring_failed=True)``. Those
rows used to be skipped on upload and excluded from every aggregate, so a
crashing judge averaged over survivors only while an honest judge averaged
over all items. Failed scores now count at their recorded value.
"""

import pytest

from opik.evaluation import report, score_statistics, test_case, test_result
from opik.evaluation.metrics import score_result


def _test_results(n_failed: int, n_ok: int):
    results = []
    trial = 0
    for _ in range(n_failed):
        results.append(
            test_result.TestResult(
                test_case=test_case.TestCase(
                    trace_id="t",
                    dataset_item_id="d",
                    task_output={},
                    dataset_item_content={},
                ),
                score_results=[
                    score_result.ScoreResult(
                        name="h", value=0.0, reason="boom", scoring_failed=True
                    )
                ],
                trial_id=trial,
            )
        )
        trial += 1
    for _ in range(n_ok):
        results.append(
            test_result.TestResult(
                test_case=test_case.TestCase(
                    trace_id="t",
                    dataset_item_id="d",
                    task_output={},
                    dataset_item_content={},
                ),
                score_results=[score_result.ScoreResult(name="h", value=1.0)],
                trial_id=trial,
            )
        )
        trial += 1
    return results


def test_aggregated_statistics_include_failed_scores():
    aggregated = score_statistics.calculate_aggregated_statistics(
        _test_results(n_failed=5, n_ok=5)
    )
    assert aggregated["h"].mean == 0.5
    assert len(aggregated["h"].values) == 10


def test_console_averages_include_failed_scores_but_still_report_them():
    average_scores, failed_scores = report._compute_average_scores(
        _test_results(n_failed=5, n_ok=5)
    )
    assert average_scores == {"h": "0.5000"}
    assert failed_scores == {"h": 5}


def _one(score: score_result.ScoreResult) -> test_result.TestResult:
    return test_result.TestResult(
        test_case=test_case.TestCase(
            trace_id="t", dataset_item_id="d", task_output={}, dataset_item_content={}
        ),
        score_results=[score],
        trial_id=0,
    )


@pytest.mark.parametrize("leftover", [float("nan"), float("inf")])
def test_a_failure_carries_0_0_into_both_paths_whatever_value_the_metric_left(
    leftover: float,
) -> None:
    """The engine records 0.0 for a failure; a custom metric may not.

    ``_build_failed_score_result`` and the other in-SDK failure paths all build
    ``value=0.0``, but ``score`` methods return a ``ScoreResult`` the caller
    constructs, so the aggregation cannot assume finiteness: before this, a
    non-finite one printed ``nan`` as the console average while the final
    statistics dropped the score entirely.
    """
    results = [
        _one(score_result.ScoreResult(name="h", value=1.0)),
        _one(
            score_result.ScoreResult(
                name="h", value=leftover, reason="boom", scoring_failed=True
            )
        ),
    ]

    average_scores, failed_scores = report._compute_average_scores(results)
    aggregated = score_statistics.calculate_aggregated_statistics(results)

    assert average_scores == {"h": "0.5000"}
    assert failed_scores == {"h": 1}
    assert aggregated["h"].mean == 0.5
    assert aggregated["h"].values == [1.0, 0.0]


@pytest.mark.parametrize("leftover", [float("nan"), float("inf")])
def test_a_metric_that_only_produces_non_finite_failures_stays_visible(
    leftover: float,
) -> None:
    """The all-failure cell: 0.0 counted, not an empty row or a dropped metric."""
    results = [
        _one(
            score_result.ScoreResult(
                name="h", value=leftover, reason="boom", scoring_failed=True
            )
        )
    ]

    average_scores, failed_scores = report._compute_average_scores(results)
    aggregated = score_statistics.calculate_aggregated_statistics(results)

    assert average_scores == {"h": "0.0000"}
    assert failed_scores == {"h": 1}
    assert aggregated["h"].mean == 0.0


@pytest.mark.parametrize("leftover", [float("nan"), float("inf")])
def test_non_finite_value_on_a_successful_score_is_untouched_by_this_change(
    leftover: float,
) -> None:
    """A pre-existing asymmetry, deliberately left alone.

    ``_is_valid_score_value`` has always excluded these from the final
    statistics while the console has always summed them, and this PR is about
    failed scores. Pinning both sides so a later change cannot quietly fold the
    successful path in here.
    """
    results = [_one(score_result.ScoreResult(name="h", value=leftover))]

    average_scores, failed_scores = report._compute_average_scores(results)
    aggregated = score_statistics.calculate_aggregated_statistics(results)

    assert average_scores == {"h": str(leftover)}
    assert failed_scores == {"h": 0}
    assert aggregated == {}
