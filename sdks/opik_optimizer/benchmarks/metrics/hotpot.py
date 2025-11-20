"""HotpotQA scoring helpers (EM + F1)."""

from __future__ import annotations

from collections import Counter
from typing import Any
import re
import string

from opik.evaluation.metrics.score_result import ScoreResult


def _normalize_answer(text: str) -> str:
    """Official HotpotQA normalization (lowercase, drop punctuation/articles)."""

    def remove_articles(s: str) -> str:
        return re.sub(r"\b(a|an|the)\b", " ", s)

    def white_space_fix(s: str) -> str:
        return " ".join(s.split())

    def remove_punc(s: str) -> str:
        return "".join(ch for ch in s if ch not in string.punctuation)

    def lower(s: str) -> str:
        return s.lower()

    return white_space_fix(remove_articles(remove_punc(lower(text))))


def _exact_match_score(prediction: str, ground_truth: str) -> float:
    return float(_normalize_answer(prediction) == _normalize_answer(ground_truth))


def _f1_score(prediction: str, ground_truth: str) -> float:
    prediction_tokens = _normalize_answer(prediction).split()
    ground_truth_tokens = _normalize_answer(ground_truth).split()

    if not prediction_tokens and not ground_truth_tokens:
        return 1.0
    if not prediction_tokens or not ground_truth_tokens:
        return 0.0

    common = Counter(prediction_tokens) & Counter(ground_truth_tokens)
    num_same = sum(common.values())
    if num_same == 0:
        return 0.0

    precision = num_same / len(prediction_tokens)
    recall = num_same / len(ground_truth_tokens)
    return (2 * precision * recall) / (precision + recall)


def hotpot_exact_match(dataset_item: dict[str, Any], llm_output: str) -> ScoreResult:
    """Exact-match accuracy on the gold Hotpot answer string."""
    gold_answer = str(dataset_item.get("answer", "") or "").strip()
    prediction = llm_output or ""
    value = _exact_match_score(prediction, gold_answer)
    reason = (
        "Exact string match after Hotpot normalization."
        if value == 1.0
        else f"Gold answer `{gold_answer}` vs model `{llm_output}`."
    )
    return ScoreResult(
        name="hotpot_exact_match",
        value=value,
        reason=reason,
        metadata={"gold_answer": gold_answer, "prediction": llm_output},
    )


def hotpot_f1(dataset_item: dict[str, Any], llm_output: str) -> ScoreResult:
    """Token-level F1 mirroring HotpotQA's official script."""
    gold_answer = str(dataset_item.get("answer", "") or "").strip()
    prediction = llm_output or ""
    value = _f1_score(prediction, gold_answer)
    return ScoreResult(
        name="hotpot_f1",
        value=value,
        reason=f"Token overlap F1 between the gold answer and `{llm_output}`.",
        metadata={"gold_answer": gold_answer},
    )
