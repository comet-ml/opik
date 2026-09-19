"""#8134 defect 1: an aggregate that drops failed scores must say how many it dropped.

``calculate_aggregated_statistics`` has never counted a metric's failed scores, so
``result.scores["m"].mean`` over 5 survivors was indistinguishable from a mean over
10 scores. These tests pin the three things that fix it: ``failed_count`` on the
aggregate, a named WARNING for the one case that still has no mean to attach the
count to (a metric whose every score failed), and what each warning is about - the
metric name, and the dataset item when one call aggregates a single item.

The survivor statistics themselves are deliberately unchanged: a failed score is
not averaged in at the ``0.0`` the engine records for it, because that would make a
crashed metric read as one that deliberately scored zero.
"""

from typing import List
from unittest import mock

import pytest
from opik.evaluation import evaluation_result, score_statistics, test_case, test_result
from opik.evaluation.metrics import score_result


def _score(
    name: str, value: float, scoring_failed: bool = False
) -> score_result.ScoreResult:
    if scoring_failed:
        return score_result.ScoreResult(
            name=name, value=value, reason="boom", scoring_failed=True
        )
    return score_result.ScoreResult(name=name, value=value)


def _result(
    dataset_item_id: str, trial_id: int, scores: List[score_result.ScoreResult]
) -> test_result.TestResult:
    return test_result.TestResult(
        test_case=test_case.TestCase(
            trace_id=f"trace-{dataset_item_id}-{trial_id}",
            dataset_item_id=dataset_item_id,
            mapped_scoring_inputs={"input": "x"},
            task_output={"output": "y"},
        ),
        score_results=scores,
        trial_id=trial_id,
    )


def _five_ok_five_failed() -> List[test_result.TestResult]:
    results = [
        _result(f"item-{i}", 1, [_score("hallucination", 1.0)]) for i in range(5)
    ]
    results += [
        _result(f"item-{i}", 1, [_score("hallucination", 0.0, scoring_failed=True)])
        for i in range(5, 10)
    ]
    return results


@pytest.fixture
def logger(monkeypatch: pytest.MonkeyPatch) -> mock.Mock:
    # Opik's logging setup disables propagation for the "opik" logger, so caplog
    # sees nothing; the module logger is asserted on directly. raising=False so
    # the assertion fails on code that has no logger at all, rather than erroring.
    spy = mock.Mock()
    monkeypatch.setattr(score_statistics, "LOGGER", spy, raising=False)
    return spy


def test_partial_failures_keep_the_survivor_mean_and_report_the_count(
    logger: mock.Mock,
):
    statistics_by_name = score_statistics.calculate_aggregated_statistics(
        _five_ok_five_failed()
    )

    stats = statistics_by_name["hallucination"]
    assert stats.mean == pytest.approx(1.0)
    assert stats.values == [1.0] * 5
    assert stats.failed_count == 5

    assert logger.warning.call_count == 1
    message = logger.warning.call_args[0][0]
    assert "Excluded 5 'hallucination'" in message
    assert "5 remaining" in message


def test_the_reported_counts_add_up_to_the_score_records_the_metric_produced():
    results = [
        _result("item-0", 1, [_score("a", 1.0), _score("b", 0.0, scoring_failed=True)]),
        _result("item-1", 1, [_score("a", 0.0, scoring_failed=True), _score("b", 1.0)]),
        _result(
            "item-2",
            1,
            [
                _score("a", 0.0, scoring_failed=True),
                _score("b", 0.0, scoring_failed=True),
            ],
        ),
    ]

    statistics_by_name = score_statistics.calculate_aggregated_statistics(results)

    attempted = {"a": 3, "b": 3}
    for name, stats in statistics_by_name.items():
        assert len(stats.values) + stats.failed_count == attempted[name]
    assert statistics_by_name["a"].mean == pytest.approx(1.0)
    assert statistics_by_name["b"].mean == pytest.approx(1.0)


def test_one_trial_can_produce_several_records_under_one_name():
    """``failed_count`` counts records, not trials, and this says which one it is.

    ``metrics_evaluator._compute_metric_scores`` extends its list with everything a
    metric returns, so one trial can contribute several scores under one name. The
    denominator therefore has to be read as records: collapsing it to trials would
    change which values the mean is taken over, which is not what this counts.
    """
    one_trial = _result(
        "item-0",
        1,
        [
            _score("multi", 1.0),
            _score("multi", 0.0),
            _score("multi", 0.0, scoring_failed=True),
        ],
    )

    stats = score_statistics.calculate_aggregated_statistics([one_trial])["multi"]

    assert stats.values == [1.0, 0.0]
    assert stats.mean == pytest.approx(0.5)
    assert stats.failed_count == 1
    # 3 records came out of 1 trial, so the sum is not a trial count.
    assert len(stats.values) + stats.failed_count == 3


def test_a_metric_that_failed_on_every_trial_is_named_in_a_warning(logger: mock.Mock):
    results = [
        _result(f"item-{i}", 1, [_score("judge", 0.0, scoring_failed=True)])
        for i in range(4)
    ]

    statistics_by_name = score_statistics.calculate_aggregated_statistics(results)

    assert "judge" not in statistics_by_name  # no denominator to average over
    assert logger.warning.call_count == 1
    message = logger.warning.call_args[0][0]
    assert "Excluded 4 'judge'" in message
    assert "0 remaining" in message
    assert "failed" in message


def test_no_failures_changes_no_survivor_statistic_and_logs_nothing(logger: mock.Mock):
    results = [
        _result(f"item-{i}", i + 1, [_score("accuracy", v)])
        for i, v in enumerate([0.7, 0.8, 0.9])
    ]

    statistics_by_name = score_statistics.calculate_aggregated_statistics(results)

    stats = statistics_by_name["accuracy"]
    assert stats.mean == pytest.approx(0.8)
    assert stats.max == 0.9
    assert stats.min == 0.7
    assert stats.values == [0.7, 0.8, 0.9]
    assert stats.std == pytest.approx(0.1, rel=1e-6)
    assert stats.failed_count == 0
    assert logger.warning.call_count == 0


def test_a_non_finite_value_on_a_successful_score_is_still_excluded_and_is_not_a_failure(
    logger: mock.Mock,
):
    results = [
        _result("item-0", 1, [_score("weird", float("inf"))]),
        _result("item-1", 2, [_score("weird", float("nan"))]),
        _result("item-2", 3, [_score("weird", 2.0)]),
    ]

    statistics_by_name = score_statistics.calculate_aggregated_statistics(results)

    stats = statistics_by_name["weird"]
    assert stats.values == [2.0]
    assert stats.mean == pytest.approx(2.0)
    assert stats.failed_count == 0
    assert logger.warning.call_count == 0


def test_the_count_reaches_the_aggregate_view_a_caller_reads():
    eval_result = evaluation_result.EvaluationResult(
        experiment_id="exp1",
        dataset_id="dataset1",
        experiment_name="experiment",
        test_results=_five_ok_five_failed(),
        experiment_url="http://test.comet.com",
        trial_count=10,
    )

    view = eval_result.aggregate_evaluation_scores()

    assert view.aggregated_scores["hallucination"].failed_count == 5
    assert view.aggregated_scores["hallucination"].mean == pytest.approx(1.0)


def test_the_per_dataset_item_view_reports_its_own_counts_per_item():
    results = [
        _result("item-1", 1, [_score("m", 1.0)]),
        _result("item-1", 2, [_score("m", 0.0, scoring_failed=True)]),
        _result("item-2", 1, [_score("m", 0.0, scoring_failed=True)]),
        _result("item-2", 2, [_score("m", 0.0, scoring_failed=True)]),
    ]
    eval_result = evaluation_result.EvaluationResult(
        experiment_id="exp1",
        dataset_id="dataset1",
        experiment_name="experiment",
        test_results=results,
        experiment_url="http://test.comet.com",
        trial_count=4,
    )

    item_results = eval_result.group_by_dataset_item_view().dataset_items

    assert item_results["item-1"].scores["m"].mean == pytest.approx(1.0)
    assert item_results["item-1"].scores["m"].failed_count == 1
    assert "m" not in item_results["item-2"].scores


def test_an_aggregation_over_one_dataset_item_says_which_one(logger: mock.Mock):
    """The grouped view calls this function once per item, so its warnings repeat.

    Without the item each call is about, a thousand-item evaluation produces a
    thousand byte-identical lines that cannot be told apart or searched for.
    """
    results = [
        _result("item-1", 1, [_score("m", 1.0)]),
        _result("item-1", 2, [_score("m", 0.0, scoring_failed=True)]),
        _result("item-2", 1, [_score("m", 0.0, scoring_failed=True)]),
        _result("item-2", 2, [_score("m", 0.0, scoring_failed=True)]),
    ]
    eval_result = evaluation_result.EvaluationResult(
        experiment_id="exp1",
        dataset_id="dataset1",
        experiment_name="experiment",
        test_results=results,
        experiment_url="http://test.comet.com",
        trial_count=4,
    )

    eval_result.group_by_dataset_item_view()

    messages = [call[0][0] for call in logger.warning.call_args_list]
    assert len(messages) == 2
    assert len(set(messages)) == 2
    assert "of dataset item 'item-1'" in messages[0]
    assert "of dataset item 'item-2'" in messages[1]


def test_an_aggregation_over_several_items_claims_no_single_item(logger: mock.Mock):
    score_statistics.calculate_aggregated_statistics(_five_ok_five_failed())

    message = logger.warning.call_args[0][0]
    assert "dataset item" not in message


def test_a_dataset_item_id_carrying_controls_cannot_forge_a_second_log_line(
    logger: mock.Mock,
):
    newline, escape = chr(10), chr(27)
    hostile_id = "id" + newline + "OPIK: every score passed" + escape + "[31mred"
    results = [
        _result(hostile_id, 1, [_score("m", 0.0, scoring_failed=True)]),
        _result(hostile_id, 2, [_score("m", 0.0, scoring_failed=True)]),
    ]

    score_statistics.calculate_aggregated_statistics(results)

    assert logger.warning.call_count == 1
    message = logger.warning.call_args[0][0]
    assert newline not in message
    assert escape not in message
    assert chr(92) + "n" in message


def test_failed_count_defaults_to_zero_so_existing_construction_still_works():
    positional = score_statistics.ScoreStatistics(1.0, 1.0, 1.0, [1.0], None)
    keyword = score_statistics.ScoreStatistics(mean=1.0, max=1.0, min=1.0, values=[1.0])

    assert positional.failed_count == 0
    assert keyword == positional


def test_a_metric_name_carrying_controls_cannot_forge_a_second_log_line(
    logger: mock.Mock,
):
    newline, escape = chr(10), chr(27)
    hostile = (
        "evil"
        + newline
        + "OPIK: every score passed"
        + newline
        + "second line"
        + escape
        + "[31mred"
    )
    results = [
        _result(f"item-{i}", 1, [_score(hostile, 0.0, scoring_failed=True)])
        for i in range(3)
    ]

    score_statistics.calculate_aggregated_statistics(results)

    assert logger.warning.call_count == 1
    message = logger.warning.call_args[0][0]
    assert newline not in message
    assert escape not in message
    assert "evil" in message
    # the controls survive only in escaped form, so the record stays one line
    assert chr(92) + "n" in message
    assert "x1b" in message


def test_an_oversized_metric_name_is_capped_in_the_log(logger: mock.Mock):
    long_name = "m" * 500
    results = [_result("item-0", 1, [_score(long_name, 0.0, scoring_failed=True)])]

    score_statistics.calculate_aggregated_statistics(results)

    message = logger.warning.call_args[0][0]
    assert "... (truncated)." in message
    assert "m" * score_statistics.MAX_LOGGED_LABEL_LENGTH not in message
    assert len(message) < 400
    assert chr(10) not in message
