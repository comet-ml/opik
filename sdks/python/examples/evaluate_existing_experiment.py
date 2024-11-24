from opik.evaluation import evaluate_experiment
from opik.evaluation.metrics import base_metric, score_result


class MyCustomMetric(base_metric.BaseMetric):
    def __init__(self, name: str):
        self.name = name

    def score(self, **ignored_kwargs):
        # Add you logic here

        return score_result.ScoreResult(
            value=10, name=self.name, reason="Optional reason for the score"
        )


evaluate_experiment(
    experiment_name="round_trellis_3225",
    scoring_metrics=[MyCustomMetric(name="custom-metric")],
)
