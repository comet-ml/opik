"""Rule-based tone metric for assistant responses."""

from __future__ import annotations

import re
from typing import Any, Iterable, Optional, Sequence

from opik.exceptions import MetricComputationError
from opik.evaluation.metrics.base_metric import BaseMetric
from opik.evaluation.metrics.score_result import ScoreResult

# Default tone lexicons/phrases kept inline for easier discoverability.
_POSITIVE_LEXICON = {
    "appreciate",
    "assist",
    "glad",
    "helpful",
    "please",
    "thank",
    "welcome",
    "happy",
    "support",
    "great",
    "excellent",
    "wonderful",
}

_NEGATIVE_LEXICON = {
    "angry",
    "awful",
    "bad",
    "complain",
    "frustrated",
    "hate",
    "incompetent",
    "terrible",
    "useless",
    "stupid",
    "idiot",
}

_FORBIDDEN_PHRASES = {
    "shut up",
    "this is pointless",
    "not my problem",
    "i refuse to assist",
}


class Tone(BaseMetric):
    """
    Flag tone issues like excessive negativity, shouting, or forbidden phrases.

    Args:
        name: Display name for the metric result. Defaults to ``"tone_metric"``.
        track: Whether to automatically track results. Defaults to ``True``.
        project_name: Optional tracking project name. Defaults to ``None``.
        min_sentiment: Minimum sentiment score required (``-1.0`` to ``1.0`` scale).
        max_upper_ratio: Maximum allowed ratio of uppercase characters.
        max_exclamations: Cap on the number of exclamation marks.
        positive_lexicon: Optional iterable of positive tokens counted for sentiment.
        negative_lexicon: Optional iterable of negative tokens counted for sentiment.
        forbidden_phrases: Optional sequence of phrases that immediately fail the
            check.

    Example:
        >>> from opik.evaluation.metrics import Tone
        >>> metric = Tone(max_exclamations=2)
        >>> result = metric.score("THANK YOU for your patience!!!")
        >>> result.value  # doctest: +SKIP
        0.0
    """

    def __init__(
        self,
        name: str = "tone_metric",
        track: bool = True,
        project_name: Optional[str] = None,
        min_sentiment: float = -0.2,
        max_upper_ratio: float = 0.3,
        max_exclamations: int = 3,
        positive_lexicon: Optional[Iterable[str]] = None,
        negative_lexicon: Optional[Iterable[str]] = None,
        forbidden_phrases: Optional[Sequence[str]] = None,
    ) -> None:
        super().__init__(name=name, track=track, project_name=project_name)
        self._min_sentiment = min_sentiment
        self._max_upper_ratio = max_upper_ratio
        self._max_exclamations = max_exclamations
        self._positive = set(
            word.lower() for word in (positive_lexicon or _POSITIVE_LEXICON)
        )
        self._negative = set(
            word.lower() for word in (negative_lexicon or _NEGATIVE_LEXICON)
        )
        phrases = forbidden_phrases or _FORBIDDEN_PHRASES
        self._forbidden = [phrase.lower() for phrase in phrases]

    def score(self, output: str, **ignored_kwargs: Any) -> ScoreResult:
        if not output or not output.strip():
            raise MetricComputationError("Text is empty (Tone metric).")

        tokens = re.findall(r"\b\w+\b", output.lower())
        if not tokens:
            raise MetricComputationError("Unable to tokenize text for Tone metric.")

        sentiment_score = self._compute_sentiment(tokens)
        upper_ratio = _uppercase_ratio(output)
        exclamation_count = output.count("!")
        forbidden_hit = any(phrase in output.lower() for phrase in self._forbidden)

        passes = (
            sentiment_score >= self._min_sentiment
            and upper_ratio <= self._max_upper_ratio
            and exclamation_count <= self._max_exclamations
            and not forbidden_hit
        )

        metadata = {
            "sentiment_score": sentiment_score,
            "uppercase_ratio": upper_ratio,
            "exclamation_count": exclamation_count,
            "forbidden_hit": forbidden_hit,
            "thresholds": {
                "min_sentiment": self._min_sentiment,
                "max_upper_ratio": self._max_upper_ratio,
                "max_exclamations": self._max_exclamations,
            },
        }

        reason = (
            "Tone is within configured guardrails"
            if passes
            else "Tone violates guardrails"
        )
        value = 1.0 if passes else 0.0
        return ScoreResult(
            value=value, name=self.name, reason=reason, metadata=metadata
        )

    def _compute_sentiment(self, tokens: Sequence[str]) -> float:
        pos_hits = sum(token in self._positive for token in tokens)
        neg_hits = sum(token in self._negative for token in tokens)
        total = pos_hits + neg_hits
        if total == 0:
            return 0.0
        return (pos_hits - neg_hits) / total


def _uppercase_ratio(text: str) -> float:
    letters = [char for char in text if char.isalpha()]
    if not letters:
        return 0.0
    upper = sum(1 for char in letters if char.isupper())
    return upper / len(letters)
