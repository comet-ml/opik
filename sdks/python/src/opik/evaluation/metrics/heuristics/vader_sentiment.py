"""VADER sentiment metric wrapper."""

from __future__ import annotations

from typing import Any, Optional

from opik.evaluation.metrics.base_metric import BaseMetric
from opik.evaluation.metrics.score_result import ScoreResult
from opik.exceptions import MetricComputationError

try:  # pragma: no cover - optional dependency
    import nltk
    from nltk.sentiment import SentimentIntensityAnalyzer
except ImportError:  # pragma: no cover - optional dependency
    nltk = None  # type: ignore
    SentimentIntensityAnalyzer = None  # type: ignore


def _build_analyzer() -> Any:
    """Return a ``SentimentIntensityAnalyzer``, fetching its lexicon if needed.

    ``SentimentIntensityAnalyzer()`` reads the ``vader_lexicon`` corpus at
    construction time and raises a bare :class:`LookupError` when it is absent,
    which is what a fresh ``pip install nltk`` gives. Download it once, as the
    METEOR metric does for WordNet, and fall back to an :class:`ImportError`
    naming the manual command when that is not possible (for example offline).
    """
    try:
        return SentimentIntensityAnalyzer()
    except LookupError:
        pass

    try:
        if nltk is not None:
            nltk.download("vader_lexicon", quiet=True)
        return SentimentIntensityAnalyzer()
    except Exception as error:
        raise ImportError(
            "VADER sentiment metric requires the NLTK corpus 'vader_lexicon'. "
            "Install manually via `python -m nltk.downloader vader_lexicon`, "
            "or provide a custom analyzer."
        ) from error


class VADERSentiment(BaseMetric):
    """
    Compute the VADER compound sentiment for a piece of text.

    References:
      - Hutto & Gilbert, "VADER: A Parsimonious Rule-based Model for Sentiment Analysis of
        Social Media Text" (ICWSM 2014)
        https://ojs.aaai.org/index.php/ICWSM/article/view/14550
      - VADER Sentiment GitHub repository (official implementation)
        https://github.com/cjhutto/vaderSentiment

    Args:
        name: Display name for the metric result. Defaults to
            ``"vader_sentiment_metric"``.
        track: Whether to automatically track metric results. Defaults to ``True``.
        project_name: Optional tracking project name. Defaults to ``None``.
        analyzer: Optional pre-initialised ``SentimentIntensityAnalyzer`` or
            compatible callable.

    Example:
        >>> from opik.evaluation.metrics import VADERSentiment
        >>> metric = VADERSentiment()
        >>> result = metric.score("I absolutely love this experience!")  # doctest: +SKIP
        >>> round(result.value, 2)  # doctest: +SKIP
        0.85
    """

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
            if (
                SentimentIntensityAnalyzer is None
            ):  # pragma: no cover - optional dependency
                raise ImportError(
                    "VADER sentiment metric requires the optional 'nltk' package. Install via"
                    " `pip install nltk` or provide a custom analyzer."
                )
            self._analyzer = _build_analyzer()

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
