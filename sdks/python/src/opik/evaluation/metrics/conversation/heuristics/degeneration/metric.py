from __future__ import annotations

import math
import re
from collections import Counter
from typing import Dict, List, Optional

from opik.evaluation.metrics.conversation import types as conversation_types
from opik.evaluation.metrics.conversation.conversation_thread_metric import (
    ConversationThreadMetric,
)
from opik.evaluation.metrics.score_result import ScoreResult
from opik.exceptions import MetricComputationError
from .phrases import DEFAULT_FALLBACK_PHRASES


def _tokenize(text: str) -> List[str]:
    return re.findall(r"\b\w+\b", text.lower())


def _ngram_counts(tokens: List[str], n: int) -> Counter:
    if len(tokens) < n:
        return Counter()
    return Counter(tuple(tokens[i : i + n]) for i in range(len(tokens) - n + 1))


class ConversationDegenerationMetric(ConversationThreadMetric):
    """
    Score how strongly an assistant conversation shows degeneration or repetition.

    The metric inspects each assistant turn, measuring repeated n-grams, overlap with
    the previous reply, low lexical diversity, and presence of known fallback
    phrases (for example, "as an AI language model..."). Each turn receives a
    degeneration score between `0.0` and `1.0`; the overall metric reports the peak
    risk observed so you can quickly flag sections where the assistant got stuck or
    stopped being helpful. Detailed per-turn diagnostics are returned in the
    ``ScoreResult.metadata`` payload.

    Args:
        name: Display name for the metric result. Defaults to
            ``"conversation_degeneration_metric"``.
        track: Whether the metric should automatically track to an Opik project.
            Defaults to ``True``.
        project_name: Optional project to store tracked results in. Defaults to
            ``None`` (inherit global setting).
        ngram_size: Size of the n-grams used to detect repetition within a single
            response. Must be at least ``2``. Defaults to ``3``.
        fallback_phrases: Custom list of phrases that should be treated as
            degeneration signatures. If ``None``, a sensible default list is used.

    Example:
        >>> from opik.evaluation.metrics import ConversationDegenerationMetric
        >>> conversation = [
        ...     {"role": "user", "content": "Can you draft a short bio for Ada?"},
        ...     {"role": "assistant", "content": "Sure, here is a short bio for Ada."},
        ...     {"role": "user", "content": "Could you add more detail?"},
        ...     {"role": "assistant", "content": "Sure, here is a short bio for Ada."},
        ... ]
        >>> metric = ConversationDegenerationMetric(ngram_size=3)
        >>> result = metric.score(conversation)
        >>> float(result.value)  # doctest: +SKIP
        0.75
    """

    def __init__(
        self,
        name: str = "conversation_degeneration_metric",
        track: bool = True,
        project_name: Optional[str] = None,
        ngram_size: int = 3,
        fallback_phrases: Optional[List[str]] = None,
    ) -> None:
        super().__init__(name=name, track=track, project_name=project_name)
        if ngram_size < 2:
            raise MetricComputationError("ngram_size must be >= 2")
        self._ngram_size = ngram_size
        phrases = (
            fallback_phrases
            if fallback_phrases is not None
            else DEFAULT_FALLBACK_PHRASES
        )
        self._fallback_phrases = [phrase.lower() for phrase in phrases]

    def score(
        self,
        conversation: conversation_types.Conversation,
        **ignored_kwargs: object,
    ) -> ScoreResult:
        assistant_turns = [
            turn["content"]
            for turn in conversation
            if turn.get("role") == "assistant" and turn.get("content")
        ]
        if not assistant_turns:
            raise MetricComputationError("Conversation contains no assistant messages")

        per_turn_metadata: List[Dict[str, float]] = []
        degeneracy_scores: List[float] = []

        prev_tokens: Optional[List[str]] = None
        for content in assistant_turns:
            tokens = _tokenize(content)
            if not tokens:
                continue

            entropy_norm = self._token_entropy(tokens)
            repetition_ratio = self._repetition_ratio(tokens)
            prev_overlap = self._overlap_with_previous(tokens, prev_tokens)
            fallback_score = 1.0 if self._contains_fallback_phrase(content) else 0.0

            normalized_entropy = 1.0 - entropy_norm
            # Combine all four risk factors with equal weight; this keeps the
            # heuristic interpretable and matches the legacy scoring behaviour.
            deg_score = min(
                1.0,
                (repetition_ratio + prev_overlap + fallback_score + normalized_entropy)
                / 4.0,
            )

            per_turn_metadata.append(
                {
                    "repetition_ratio": repetition_ratio,
                    "overlap_previous": prev_overlap,
                    "fallback_hit": fallback_score,
                    "normalized_entropy": normalized_entropy,
                    "degeneration_score": deg_score,
                }
            )
            degeneracy_scores.append(deg_score)
            prev_tokens = tokens

        if not degeneracy_scores:
            raise MetricComputationError(
                "Assistant messages were empty after tokenization"
            )

        average_score = sum(degeneracy_scores) / len(degeneracy_scores)
        peak_score = max(degeneracy_scores)

        return ScoreResult(
            value=peak_score,
            name=self.name,
            reason=(
                f"Peak degeneration risk ({len(degeneracy_scores)} turns):"
                f" {peak_score:.3f}"
            ),
            metadata={
                "per_turn": per_turn_metadata,
                "average_score": average_score,
                "peak_score": peak_score,
            },
        )

    def _token_entropy(self, tokens: List[str]) -> float:
        counts = Counter(tokens)
        total = float(len(tokens))
        entropy = 0.0
        for count in counts.values():
            prob = count / total
            entropy -= prob * math.log(prob, 2)
        max_entropy = math.log(len(counts), 2) if counts else 1.0
        if max_entropy == 0:
            return 0.0
        return min(1.0, entropy / max_entropy)

    def _repetition_ratio(self, tokens: List[str]) -> float:
        ngram_counts = _ngram_counts(tokens, self._ngram_size)
        total = sum(ngram_counts.values())
        if total == 0:
            return 0.0
        repeated = sum(count for count in ngram_counts.values() if count > 1)
        return repeated / total

    def _overlap_with_previous(
        self, tokens: List[str], prev_tokens: Optional[List[str]]
    ) -> float:
        if not prev_tokens:
            return 0.0
        current_set = set(tokens)
        prev_set = set(prev_tokens)
        if not current_set or not prev_set:
            return 0.0
        intersection = len(current_set & prev_set)
        union = len(current_set | prev_set)
        return intersection / union

    def _contains_fallback_phrase(self, content: str) -> bool:
        lowered = content.lower()
        return any(phrase in lowered for phrase in self._fallback_phrases)
