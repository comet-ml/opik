from __future__ import annotations

import math
import re
from collections import Counter
from typing import Dict, List, Optional

from opik.evaluation.metrics.base_metric import BaseMetric
from opik.evaluation.metrics.score_result import ScoreResult
from opik.evaluation.metrics.conversation import types as conversation_types


def _tokenize(text: str) -> List[str]:
    return re.findall(r"\b\w+\b", text.lower())


def _ngram_counts(tokens: List[str], n: int) -> Counter:
    if len(tokens) < n:
        return Counter()
    return Counter(tuple(tokens[i : i + n]) for i in range(len(tokens) - n + 1))


class ConversationDegenerationMetric(BaseMetric):
    """Detect repetition/degeneration patterns across assistant turns.

    Scores range from 0 (no degeneration risk) to 1 (high repetition/degeneration).
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
            raise ValueError("ngram_size must be >= 2")
        self._ngram_size = ngram_size
        self._fallback_phrases = (
            [
                "i'm sorry",
                "as an ai",
                "i cannot",
                "i'm unable",
                "please provide",
            ]
            if fallback_phrases is None
            else [phrase.lower() for phrase in fallback_phrases]
        )

    def score(
        self,
        conversation: conversation_types.Conversation,
        **ignored_kwargs: object,
    ) -> score_result.ScoreResult:
        assistant_turns = [
            turn["content"]
            for turn in conversation
            if turn.get("role") == "assistant" and turn.get("content")
        ]
        if not assistant_turns:
            raise ValueError("Conversation contains no assistant messages")

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
            raise ValueError("Assistant messages were empty after tokenization")

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
