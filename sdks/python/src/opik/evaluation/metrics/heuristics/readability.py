"""Deterministic readability guardrails for generated text."""

from __future__ import annotations

import re
from typing import Any, Optional

from opik.evaluation.metrics.base_metric import BaseMetric
from opik.evaluation.metrics.score_result import ScoreResult
from opik.exceptions import MetricComputationError


_VOWELS = "aeiouy"


def _count_syllables(word: str) -> int:
    word = word.lower().strip()
    if not word:
        return 0
    word = re.sub(r"[^a-z]", "", word)
    if not word:
        return 0
    syllable_count = 0
    prev_vowel = False
    for char in word:
        is_vowel = char in _VOWELS
        if is_vowel and not prev_vowel:
            syllable_count += 1
        prev_vowel = is_vowel
    if word.endswith("e") and syllable_count > 1:
        syllable_count -= 1
    return max(1, syllable_count)


def _split_sentences(text: str) -> list[str]:
    sentences = re.split(r"[.!?]+", text)
    return [sentence.strip() for sentence in sentences if sentence.strip()]


class ReadabilityGuard(BaseMetric):
    """Checks whether text falls within configured readability bounds."""

    def __init__(
        self,
        name: str = "readability_guard",
        track: bool = True,
        project_name: Optional[str] = None,
        min_grade: Optional[float] = None,
        max_grade: Optional[float] = None,
    ) -> None:
        super().__init__(name=name, track=track, project_name=project_name)
        self._min_grade = min_grade
        self._max_grade = max_grade

    def score(
        self,
        output: str,
        **ignored_kwargs: Any,
    ) -> ScoreResult:
        if not output or not output.strip():
            raise MetricComputationError("Text is empty (ReadabilityGuard).")

        sentences = _split_sentences(output)
        words = re.findall(r"\b\w+\b", output)
        if not sentences or not words:
            raise MetricComputationError(
                "Unable to parse text for readability metrics."
            )

        syllables = sum(_count_syllables(word) for word in words)
        words_per_sentence = len(words) / len(sentences)
        syllables_per_word = syllables / len(words)

        reading_ease = 206.835 - 1.015 * words_per_sentence - 84.6 * syllables_per_word
        fk_grade = 0.39 * words_per_sentence + 11.8 * syllables_per_word - 15.59

        within_bounds = self._is_within_grade_bounds(fk_grade)
        value = 1.0 if within_bounds else 0.0
        reason = (
            "Text meets readability targets"
            if within_bounds
            else "Text falls outside readability targets"
        )

        metadata = {
            "flesch_reading_ease": reading_ease,
            "flesch_kincaid_grade": fk_grade,
            "words_per_sentence": words_per_sentence,
            "syllables_per_word": syllables_per_word,
            "min_grade": self._min_grade,
            "max_grade": self._max_grade,
        }

        return ScoreResult(
            value=value, name=self.name, reason=reason, metadata=metadata
        )

    def _is_within_grade_bounds(self, grade: float) -> bool:
        if self._min_grade is not None and grade < self._min_grade:
            return False
        if self._max_grade is not None and grade > self._max_grade:
            return False
        return True
