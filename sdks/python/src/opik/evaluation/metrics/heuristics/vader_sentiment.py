"""VADER sentiment metric wrapper."""

from __future__ import annotations

from typing import Any, Optional

from opik.evaluation.metrics.base_metric import BaseMetric
from opik.evaluation.metrics.score_result import ScoreResult
from opik.exceptions import MetricComputationError

try:  # pragma: no cover - optional dependency
    from nltk.sentiment import SentimentIntensityAnalyzer
except ImportError:  # pragma: no cover - optional dependency
    SentimentIntensityAnalyzer = None  # type: ignore


class VADERSentiment(BaseMetric):
    """Compute VADER sentiment scores for a piece of text."""

    def __init__(
        self,
        name: str = "vader_sentiment_metric",
        track: bool = True,
        project_name: Optional[str] = None,
        analyzer: Optional[Any] = None,
    ) -> None:
        super().__init__(name=name, track=track, project_name=project_name)

        if analyzer is not None:
            self._analyzer = analyzer
        else:
            if SentimentIntensityAnalyzer is None:  # pragma: no cover - optional dependency
                raise ImportError(
                    "VADER sentiment metric requires the optional 'nltk' package. Install via"
                    " `pip install nltk` or provide a custom analyzer."
                )
            self._analyzer = SentimentIntensityAnalyzer()

    def score(self, output: str, **ignored_kwargs: Any) -> ScoreResult:
        if not output or not output.strip():
            raise MetricComputationError("Text is empty (VADERSentiment).")

        scores = self._analyzer.polarity_scores(output)
        compound = float(scores.get("compound", 0.0))
        normalized = (compound + 1.0) / 2.0
        return ScoreResult(
            value=normalized,
            name=self.name,
            reason=f"VADER compound score (normalized): {normalized:.4f}",
            metadata={"vader": scores},
        )
