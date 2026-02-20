from typing import Any

from opik.evaluation.engine.metrics_evaluator import MetricsEvaluator
from opik.evaluation.metrics import base_metric, score_result


class ExplodingMetric(base_metric.BaseMetric):
    def __init__(self) -> None:
        super().__init__(name="exploding_metric", track=False)

    def score(
        self, output: str, reference: str, **_ignored_kwargs: Any
    ) -> score_result.ScoreResult:
        _ = output, reference
        raise ValueError("judge parse failure")


def test_metrics_evaluator__metric_exception__marks_failed_with_error_metadata() -> (
    None
):
    evaluator = MetricsEvaluator(
        scoring_metrics=[ExplodingMetric()],
        scoring_key_mapping=None,
    )

    score_results, _ = evaluator.compute_regular_scores(
        dataset_item_content={"reference": "hello"},
        task_output={"output": "hello"},
    )

    assert len(score_results) == 1
    metric_score = score_results[0]
    assert metric_score.name == "exploding_metric"
    assert metric_score.value == 0.0
    assert metric_score.scoring_failed is True
    assert metric_score.metadata == {
        "_error_type": "ValueError",
        "_error_message": "judge parse failure",
    }
