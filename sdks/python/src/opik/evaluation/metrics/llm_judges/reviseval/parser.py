from __future__ import annotations

import json

import opik.exceptions as exceptions
from opik.evaluation.metrics.llm_judges import parsing_helpers


def parse_model_output(content: str) -> tuple[float, str]:
    try:
        parsed = parsing_helpers.extract_json_content_or_raise(content)
    except exceptions.MetricComputationError:
        parsed = json.loads(content)

    score = float(parsed["score"])
    reason = str(parsed.get("reason", ""))
    if not (0.0 <= score <= 1.0):
        raise exceptions.MetricComputationError(
            "RevisEval score must be between 0 and 1"
        )
    return score, reason
