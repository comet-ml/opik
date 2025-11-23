"""HoVer accuracy and judge feedback metrics."""

from __future__ import annotations

from typing import Any, cast
from collections.abc import Iterable

from opik.evaluation.metrics.score_result import ScoreResult
from pydantic import BaseModel

from . import utils

HOVER_LABELS = {0: "NOT_SUPPORTED", 1: "SUPPORTED"}
HOVER_JUDGE_MODEL = "openai/gpt-4.1"
HOVER_JUDGE_TEMPERATURE = 0.0


def _normalize_hover_label(raw_label: Any) -> str | None:
    if isinstance(raw_label, int):
        return HOVER_LABELS.get(raw_label)
    if isinstance(raw_label, str):
        label = raw_label.strip().upper()
        if "NOT" in label and "SUPPORT" in label:
            return "NOT_SUPPORTED"
        if "REFUTED" in label:
            return "NOT_SUPPORTED"
        if "SUPPORTED" in label:
            return "SUPPORTED"
    return None


def _format_supporting_facts(facts: Iterable[Any] | None) -> str:
    rows = []
    for fact in facts or []:
        if isinstance(fact, dict):
            rows.append(
                f"- {fact.get('key', 'unknown')} (sentence {fact.get('value')})"
            )
        else:
            rows.append(f"- {fact}")
    return "\n".join(rows) if rows else "No supporting facts provided."


def hover_label_accuracy(dataset_item: dict[str, Any], llm_output: str) -> ScoreResult:
    """Binary accuracy on SUPPORTED/NOT_SUPPORTED decisions."""
    gold_label = _normalize_hover_label(dataset_item.get("label"))
    predicted = _normalize_hover_label(llm_output)
    value = float(predicted == gold_label) if (gold_label and predicted) else 0.0

    if not gold_label:
        reason = "Gold label missing from dataset row."
    elif not predicted:
        reason = (
            "Model output did not contain a recognizable HoVer label "
            "(expected SUPPORTED or NOT_SUPPORTED)."
        )
    elif value == 1.0:
        reason = f"Matched HoVer label `{gold_label}`."
    else:
        reason = f"Expected `{gold_label}` but model predicted `{predicted}`."

    return ScoreResult(
        name="hover_accuracy",
        value=value,
        reason=reason,
        metadata={"gold_label": gold_label, "prediction": predicted},
    )


class HoverJudgeResponse(BaseModel):
    label_match: bool
    explanation: str
    score: float | None = None  # Optional graded score in [0,1]
    suggestions: str | None = None


HOVER_JUDGE_PROMPT = """You are an expert judge evaluating a HoVer claim-verification run.
Compare the model's verdict with the gold label and explain the decision quality.

Guidance for scoring:
- The model verdict should match the gold label (SUPPORTED or NOT_SUPPORTED).
- Be vigilant for partial hallucinations or misattributions (e.g., wrong entity/date pairing).
- Consider whether the model introduces unsupported facts or contradicts the gold label/facts.
- Assign a score in [0, 1] where:
  * 1.0   = completely wrong / unfaithful to the gold label and facts
  * 0.0   = perfectly faithful to the gold label and facts
  * Intermediate values reflect partial correctness or minor issues.

Return strict JSON with: label_match (boolean), score (0-1 float), explanation, suggestions.

Claim:
{claim}

Gold label: {gold_label}
Gold supporting facts:
{supporting_facts}

Model verdict / reasoning:
{prediction}
"""


def hover_judge_feedback(dataset_item: dict[str, Any], llm_output: str) -> ScoreResult:
    """LLM-as-judge feedback used by HoVer."""
    gold_label = _normalize_hover_label(dataset_item.get("label")) or "UNKNOWN"
    supporting_facts = cast(Iterable[Any] | None, dataset_item.get("supporting_facts"))
    prompt = HOVER_JUDGE_PROMPT.format(
        claim=str(dataset_item.get("claim", "")),
        gold_label=gold_label,
        supporting_facts=_format_supporting_facts(supporting_facts),
        prediction=llm_output or "",
    )
    response, raw_output = utils.run_structured_judge(
        prompt=prompt,
        response_model=HoverJudgeResponse,
        model_name=HOVER_JUDGE_MODEL,
        temperature=HOVER_JUDGE_TEMPERATURE,
    )

    if response is None:
        return ScoreResult(
            name="hover_judge",
            value=0.0,
            reason="HoVer judge returned un-parseable output.",
            metadata={"raw_judge_output": raw_output},
            scoring_failed=True,
        )

    score = (
        max(0.0, min(1.0, float(response.score)))
        if response.score is not None
        else (1.0 if response.label_match else 0.0)
    )

    return ScoreResult(
        name="hover_judge",
        value=score,
        reason=response.explanation,
        metadata={
            "suggestions": response.suggestions,
            "judge_model": HOVER_JUDGE_MODEL,
            "label_match": response.label_match,
            "raw_score": response.score,
        },
    )
