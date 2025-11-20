"""Shared helpers for the GEPA metric modules."""

from __future__ import annotations

from typing import TypeVar
import re

from opik.evaluation.models import models_factory
from pydantic import BaseModel, ValidationError

_JSON_PATTERN = re.compile(r"\{.*\}", flags=re.DOTALL)
JudgeResponse = TypeVar("JudgeResponse", bound=BaseModel)


def extract_json_blob(raw: str) -> str:
    """Best-effort extraction of the first JSON object in an LLM response."""
    content = raw.strip()
    if content.startswith("```"):
        stripped = content.strip("`")
        if "\n" in stripped:
            content = stripped.split("\n", 1)[1]
        else:
            content = stripped

    match = _JSON_PATTERN.search(content)
    if match:
        return match.group(0)
    return content


def run_structured_judge(
    *,
    prompt: str,
    response_model: type[JudgeResponse],
    model_name: str,
    temperature: float = 0.0,
) -> tuple[JudgeResponse | None, str]:
    """
    Execute a structured judge prompt via LiteLLM and parse the JSON payload.

    Callers must ensure that LiteLLM-compatible credentials (e.g. OPENAI_API_KEY)
    are available in the environment.
    """
    llm = models_factory.get(model_name=model_name, temperature=temperature)
    raw_output = llm.generate_string(input=prompt)

    for candidate in (raw_output, extract_json_blob(raw_output)):
        try:
            return response_model.model_validate_json(candidate), raw_output
        except ValidationError:
            continue
    return None, raw_output
