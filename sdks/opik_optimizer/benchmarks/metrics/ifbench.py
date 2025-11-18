"""IFBench constraint-compliance judge."""

from __future__ import annotations

from typing import Any
import json

from opik.evaluation.metrics.score_result import ScoreResult
from pydantic import BaseModel

from . import utils

IFBENCH_JUDGE_MODEL = "openai/gpt-4.1-mini"
IFBENCH_TEMPERATURE = 0.0


def _format_constraints(dataset_item: dict[str, Any]) -> str:
    instructions = dataset_item.get("instruction_id_list") or []
    kwargs = dataset_item.get("kwargs") or []
    lines: list[str] = []
    for idx, constraint in enumerate(instructions):
        payload = kwargs[idx] if idx < len(kwargs) else {}
        pretty_payload = json.dumps(payload, sort_keys=True, ensure_ascii=False)
        lines.append(f"- {constraint}: {pretty_payload}")
    return "\n".join(lines) if lines else "No explicit constraint metadata."


class IFBenchJudgeResponse(BaseModel):
    compliant: bool
    explanation: str
    violated_constraints: list[str] | None = None


IFBENCH_JUDGE_PROMPT = """\
You are auditing IFBench instruction-following tasks.
Decide whether the assistant response satisfies every listed constraint.
Return JSON with compliant (boolean), explanation, and violated_constraints (list).

User request:
{prompt}

Constraint summary:
{constraints}

System response:
{prediction}
"""


def ifbench_compliance_judge(
    dataset_item: dict[str, Any], llm_output: str
) -> ScoreResult:
    prompt = IFBENCH_JUDGE_PROMPT.format(
        prompt=str(dataset_item.get("prompt", "")),
        constraints=_format_constraints(dataset_item),
        prediction=llm_output or "",
    )
    response, raw_output = utils.run_structured_judge(
        prompt=prompt,
        response_model=IFBenchJudgeResponse,
        model_name=IFBENCH_JUDGE_MODEL,
        temperature=IFBENCH_TEMPERATURE,
    )
    if response is None:
        return ScoreResult(
            name="ifbench_compliance",
            value=0.0,
            reason="IFBench judge response could not be parsed.",
            metadata={"raw_judge_output": raw_output},
            scoring_failed=True,
        )

    return ScoreResult(
        name="ifbench_compliance",
        value=1.0 if response.compliant else 0.0,
        reason=response.explanation,
        metadata={
            "violated_constraints": response.violated_constraints,
            "judge_model": IFBENCH_JUDGE_MODEL,
        },
    )
