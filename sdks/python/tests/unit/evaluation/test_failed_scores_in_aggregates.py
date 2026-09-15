"""#8134 defect 1: failed metric scores must not be silently dropped.

A metric exception produces ``ScoreResult(0.0, scoring_failed=True)``. Those
rows used to be skipped on upload and excluded from every aggregate, so a
crashing judge averaged over survivors only while an honest judge averaged
over all items. Failed scores now count at their recorded value.
"""

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
