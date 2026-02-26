"""IFBench constraint-compliance judge."""

from __future__ import annotations

from typing import Any, TypeVar
import json
import re

from opik.evaluation.metrics.score_result import ScoreResult
from opik.evaluation.models import models_factory
from pydantic import BaseModel, ValidationError

IFBENCH_JUDGE_MODEL = "openai/gpt-4.1-mini"
IFBENCH_TEMPERATURE = 0.0
_JSON_PATTERN = re.compile(r"\{.*\}", flags=re.DOTALL)
JudgeResponse = TypeVar("JudgeResponse", bound=BaseModel)


def _extract_json_blob(raw: str) -> str:
    content = raw.strip()
    match = _JSON_PATTERN.search(content)
    return match.group(0) if match else content


def _run_structured_judge(
    *,
    prompt: str,
    response_model: type[JudgeResponse],
    model_name: str,
    temperature: float = 0.0,
) -> tuple[JudgeResponse | None, str]:
    llm = models_factory.get(model_name=model_name, temperature=temperature)
    raw_output = llm.generate_string(input=prompt)
    for candidate in (raw_output, _extract_json_blob(raw_output)):
        try:
            return response_model.model_validate_json(candidate), raw_output
        except ValidationError:
            continue
    return None, raw_output


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
    response, raw_output = _run_structured_judge(
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
