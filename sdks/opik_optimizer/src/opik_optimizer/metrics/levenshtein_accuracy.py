from typing import Any

from opik.evaluation.metrics import LevenshteinRatio, base_metric, score_result


class LevenshteinAccuracyMetric(base_metric.BaseMetric):
    """Dataset-keyed Levenshtein accuracy metric for optimizer examples and quickstarts."""

    def __init__(
        self,
        reference_key: str = "answer",
        output_key: str = "output",
        name: str = "accuracy",
        track: bool = True,
        project_name: str | None = None,
    ) -> None:
        super().__init__(name=name, track=track, project_name=project_name)
        self.reference_key = reference_key
        self.output_key = output_key
        self._metric = LevenshteinRatio(name=name)

    def score(self, **kwargs: Any) -> score_result.ScoreResult:
        reference = kwargs.get(self.reference_key)
        output = kwargs.get(self.output_key)

        if reference is None:
            return score_result.ScoreResult(
                name=self.name,
                value=0.0,
                reason=f"Missing reference field '{self.reference_key}'.",
                scoring_failed=True,
            )
        if output is None:
            return score_result.ScoreResult(
                name=self.name,
                value=0.0,
                reason=f"Missing output field '{self.output_key}'.",
                scoring_failed=True,
            )

        result = self._metric.score(reference=str(reference), output=str(output))
        if result.reason:
            return result
        return score_result.ScoreResult(
            name=result.name or self.name,
            value=float(result.value),
            reason=f"Levenshtein similarity={float(result.value):.3f}.",
            metadata=result.metadata,
            scoring_failed=result.scoring_failed,
        )
