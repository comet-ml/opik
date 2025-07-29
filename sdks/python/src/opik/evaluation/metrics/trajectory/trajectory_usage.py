from opik.evaluation.metrics.base_metric import BaseMetric
from opik.evaluation.metrics.score_result import ScoreResult


class TrajectoryUsage(BaseMetric):
    def __init__(self, track: bool = True):
        super().__init__(track=track)

    @property
    def name(self) -> str:
        return "trajectory_usage"

    def score(self, trajectory: dict) -> ScoreResult:
        return ScoreResult(
            name=self.name,
            score=1,
            reason=None,
            metadata={
                "total_tokens": trajectory.get("total_tokens"),
                "total_cost": trajectory.get("total_cost"),
            },
        )
