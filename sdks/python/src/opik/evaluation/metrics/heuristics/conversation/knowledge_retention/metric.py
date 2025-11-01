"""Heuristic metric for knowledge retention across conversation turns."""

from __future__ import annotations

import functools
from typing import Callable, List, Optional, Sequence, Set

from opik.evaluation.metrics.conversation import types as conversation_types
from opik.evaluation.metrics.conversation.conversation_thread_metric import (
    ConversationThreadMetric,
)
from opik.evaluation.metrics.score_result import ScoreResult
from opik.evaluation.preprocessing import normalize_text

_MIN_TOKEN_LENGTH = 4
_STOPWORDS = {
    "the",
    "this",
    "that",
    "with",
    "have",
    "from",
    "about",
    "there",
    "which",
    "would",
    "could",
    "should",
    "name",
    "number",
}

_REQUEST_KEYWORDS = {
    "need",
    "please",
    "help",
    "can",
    "could",
    "would",
    "should",
    "shall",
    "may",
    "might",
    "want",
    "tell",
    "explain",
    "show",
    "give",
    "provide",
    "let",
    "allow",
    "request",
}


class KnowledgeRetentionMetric(ConversationThreadMetric):
    """
    Measure how many user-provided facts resurface in the closing assistant reply.

    The metric extracts salient, non-stopword terms from earlier user turns,
    excluding explicit requests (for example, questions or pleas for help). When the
    final assistant response omits those key terms, the score drops, signalling the
    assistant may have forgotten essential context. Scores range from `0.0`
    (nothing retained) to `1.0` (all extracted facts referenced).

    Args:
        name: Display name for the metric result. Defaults to
            ``"knowledge_retention_metric"``.
        track: Whether the metric should automatically track results. Defaults to
            ``True``.
        project_name: Optional Opik project name for tracking. Defaults to ``None``.
        turns_to_consider: How many of the earliest user turns should be mined for
            facts. Defaults to ``5``.

    Example:
        >>> from opik.evaluation.metrics import KnowledgeRetentionMetric
        >>> conversation = [
        ...     {"role": "user", "content": "My new router is a Netgear Nighthawk."},
        ...     {"role": "assistant", "content": "Great choice!"},
        ...     {"role": "user", "content": "Please remind me about the Netgear router setup."},
        ...     {
        ...         "role": "assistant",
        ...         "content": "Be sure to update the Netgear Nighthawk firmware first.",
        ...     },
        ... ]
        >>> metric = KnowledgeRetentionMetric(turns_to_consider=3)
        >>> result = metric.score(conversation)
        >>> float(result.value)  # doctest: +SKIP
        1.0
    """

    def __init__(
        self,
        name: str = "knowledge_retention_metric",
        track: bool = True,
        project_name: Optional[str] = None,
        turns_to_consider: int = 5,
    ) -> None:
        super().__init__(name=name, track=track, project_name=project_name)
        self._turns_to_consider = turns_to_consider
        self._normalizer: Callable[[str], str] = functools.partial(
            normalize_text,
            keep_emoji=False,
            remove_punctuation=True,
        )

    def score(
        self,
        conversation: conversation_types.Conversation,
        **ignored_kwargs: object,
    ) -> ScoreResult:
        user_turns: List[str] = []
        assistant_turns: List[str] = []
        for message in conversation:
            content = message.get("content")
            role = message.get("role")
            if not content or not role:
                continue
            if role == "user":
                if self._is_user_request(content):
                    continue
                user_turns.append(content)
            elif role == "assistant":
                assistant_turns.append(content)

        if not assistant_turns:
            return ScoreResult(
                value=0.0, name=self.name, reason="No assistant turns", metadata={}
            )

        reference_facts = self._extract_terms(user_turns[: self._turns_to_consider])
        if not reference_facts:
            return ScoreResult(
                value=1.0, name=self.name, reason="No facts to retain", metadata={}
            )

        final_response = assistant_turns[-1]
        final_terms = self._extract_terms([final_response])

        retained = reference_facts & final_terms
        score = len(retained) / len(reference_facts)

        metadata = {
            "reference_terms": sorted(reference_facts),
            "retained_terms": sorted(retained),
        }

        return ScoreResult(
            value=score,
            name=self.name,
            reason=f"Retained {len(retained)} of {len(reference_facts)} reference terms",
            metadata=metadata,
        )

    def _extract_terms(self, texts: Sequence[str]) -> Set[str]:
        tokens: Set[str] = set()
        for text in texts:
            normalized = self._normalizer(text)
            for token in normalized.split():
                token = token.strip()
                if len(token) < _MIN_TOKEN_LENGTH or token in _STOPWORDS:
                    continue
                tokens.add(token)
        return tokens

    def _is_user_request(self, text: str) -> bool:
        if "?" in text:
            return True

        normalized = self._normalizer(text)
        tokens = set(normalized.split())
        return any(token in _REQUEST_KEYWORDS for token in tokens)
