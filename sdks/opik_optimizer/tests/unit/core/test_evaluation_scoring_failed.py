from opik.evaluation.metrics.score_result import ScoreResult

from opik_optimizer.core import evaluation


def test_average_finite_scores_ignores_failed_scores_even_if_non_zero() -> None:
    scores = [
        ScoreResult(name="objective", value=0.95, scoring_failed=True),
        ScoreResult(name="objective", value=0.4, scoring_failed=False),
    ]

    averaged = evaluation._average_finite_scores(
        scores,
        objective_metric_name="objective",
    )

    assert averaged == 0.4
