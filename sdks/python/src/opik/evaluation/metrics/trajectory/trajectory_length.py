from opik.evaluation.metrics.base_metric import BaseMetric
from opik.evaluation.metrics.score_result import ScoreResult


class TrajectoryLength(BaseMetric):
    def __init__(self, track: bool = True):
        super().__init__(track=track)

    @property
    def name(self) -> str:
        return "trajectory_length"

    def score(self, trajectory: list) -> ScoreResult:
        return ScoreResult(
            name=self.name,
            score=len(trajectory),
            reason=None,
            metadata=None,
        )
