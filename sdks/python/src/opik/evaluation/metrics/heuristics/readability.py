"""Readability heuristics backed by the ``textstat`` library."""

from __future__ import annotations

from typing import Any, Optional

from opik.evaluation.metrics.base_metric import BaseMetric
from opik.evaluation.metrics.score_result import ScoreResult
from opik.exceptions import MetricComputationError

try:  # pragma: no cover - optional dependency
    import textstat as _textstat_lib
except ImportError:  # pragma: no cover - optional dependency
    _textstat_lib = None


class Readability(BaseMetric):
    """Compute common readability statistics using ``textstat``.

    The metric reports the Flesch Reading Ease (0–100) alongside the Flesch–Kincaid
    grade level. The score value is the reading-ease score normalised to ``[0, 1]``.
    You can optionally enforce grade bounds to turn the metric into a guardrail.

    Args:
        name: Display name for the metric result.
        track: Whether to automatically track metric results.
        project_name: Optional tracking project name.
        min_grade: Inclusive lower bound for the acceptable grade.
        max_grade: Inclusive upper bound for the acceptable grade.
        language: Locale forwarded to ``textstat`` when counting syllables.
        textstat_module: Optional ``textstat``-compatible module for dependency
            injection (mainly used in tests).
        enforce_bounds: When ``True`` the metric returns ``1.0`` if the grade lies
            within bounds and ``0.0`` otherwise, effectively acting as a guardrail.
    """

    def __init__(
        self,
        *,
        name: str = "readability_metric",
        track: bool = True,
        project_name: Optional[str] = None,
        min_grade: Optional[float] = None,
        max_grade: Optional[float] = None,
        language: str = "en_US",
        textstat_module: Optional[Any] = None,
        enforce_bounds: bool = False,
    ) -> None:
        super().__init__(name=name, track=track, project_name=project_name)
        if textstat_module is not None:
            self._textstat = textstat_module
        else:
            if _textstat_lib is None:  # pragma: no cover - optional dependency
                raise ImportError(
                    "Readability metric requires the optional 'textstat' package. "
                    "Install via `pip install textstat`."
                )
            self._textstat = _textstat_lib

        self._min_grade = min_grade
        self._max_grade = max_grade
        self._language = language
        self._enforce_bounds = enforce_bounds

    def score(
        self,
        output: str,
        **ignored_kwargs: Any,
    ) -> ScoreResult:
        if not output or not output.strip():
            raise MetricComputationError("Text is empty (Readability metric).")

        cleaned = output.strip()
        sentence_count = self._textstat.sentence_count(cleaned)
        word_count = self._textstat.lexicon_count(cleaned, removepunct=True)
        if sentence_count <= 0 or word_count <= 0:
            raise MetricComputationError(
                "Unable to parse text for readability metrics."
            )

        syllable_count = self._textstat.syllable_count(cleaned, lang=self._language)
        reading_ease = float(self._textstat.flesch_reading_ease(cleaned))
        fk_grade = float(self._textstat.flesch_kincaid_grade(cleaned))

        words_per_sentence = word_count / sentence_count
        syllables_per_word = syllable_count / word_count if word_count else 0.0
        within_bounds = self._is_within_grade_bounds(fk_grade)

        if self._enforce_bounds:
            value = 1.0 if within_bounds else 0.0
            reason = (
                "Text meets readability targets"
                if within_bounds
                else "Text falls outside readability targets"
            )
        else:
            normalised = max(0.0, min(100.0, reading_ease)) / 100.0
            value = normalised
            reason = (
                f"Flesch Reading Ease: {reading_ease:.2f} | "
                f"Flesch-Kincaid Grade: {fk_grade:.2f}"
            )

        metadata = {
            "flesch_reading_ease": reading_ease,
            "flesch_kincaid_grade": fk_grade,
            "words_per_sentence": words_per_sentence,
            "syllables_per_word": syllables_per_word,
            "sentence_count": sentence_count,
            "word_count": word_count,
            "syllable_count": syllable_count,
            "min_grade": self._min_grade,
            "max_grade": self._max_grade,
            "within_grade_bounds": within_bounds,
        }

        return ScoreResult(
            value=value,
            name=self.name,
            reason=reason,
            metadata=metadata,
        )

    def _is_within_grade_bounds(self, grade: float) -> bool:
        if self._min_grade is not None and grade < self._min_grade:
            return False
        if self._max_grade is not None and grade > self._max_grade:
            return False
        return True
