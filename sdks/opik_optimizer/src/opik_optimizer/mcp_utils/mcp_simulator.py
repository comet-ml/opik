"""Deterministic harness to score MCP tool usage during optimization."""

from __future__ import annotations

from dataclasses import dataclass
from difflib import SequenceMatcher
from typing import Any
from collections.abc import Callable, Mapping

from .mcp import ToolSignature, validate_tool_arguments


@dataclass
class ToolCallResult:
    tool_name: str
    arguments: Mapping[str, Any]
    response: Any


@dataclass
class SimulationReport:
    dataset_id: str
    expected_tool: str
    tool_called: bool
    called_tool: str | None
    arguments_valid: bool
    score: float
    response: Any
    failure_reason: str | None


InvokeFn = Callable[[ToolSignature, Mapping[str, Any], dict[str, Any]], ToolCallResult]


def simulate_session(
    signature_map: dict[str, ToolSignature],
    dataset_item: dict[str, Any],
    invoke_tool: InvokeFn | None = None,
) -> SimulationReport:
    """Simulate a tool invocation for ``dataset_item`` using ``signature_map``.

    ``invoke_tool`` can run a real MCP client; when absent we assume the
    expected tool is called with the reference arguments for deterministic
    scoring.
    """

    dataset_id = dataset_item.get("id", "unknown")
    expected_tool = dataset_item["expected_tool"]
    reference_arguments = dataset_item.get("arguments", {})
    reference_answer = dataset_item.get("reference_answer") or dataset_item.get(
        "expected_answer_contains"
    )

    signature = signature_map.get(expected_tool)
    if signature is None:
        return SimulationReport(
            dataset_id=dataset_id,
            expected_tool=expected_tool,
            tool_called=False,
            called_tool=None,
            arguments_valid=False,
            score=0.0,
            response=None,
            failure_reason="missing_tool_signature",
        )

    if invoke_tool is None:
        tool_call = ToolCallResult(
            tool_name=expected_tool,
            arguments=reference_arguments,
            response=dataset_item.get("reference_response", reference_answer),
        )
    else:
        tool_call = invoke_tool(signature, reference_arguments, dataset_item)

    arguments_valid, validation_message = validate_tool_arguments(
        signature, tool_call.arguments
    )

    tool_called = tool_call.tool_name is not None
    called_tool = tool_call.tool_name
    failure_reason = None
    score = 0.0

    if not tool_called:
        failure_reason = "tool_not_called"
    elif called_tool != expected_tool:
        failure_reason = "wrong_tool"
    elif not arguments_valid:
        failure_reason = f"invalid_arguments:{validation_message}"
    else:
        response_text = (
            str(tool_call.response) if tool_call.response is not None else ""
        )
        if reference_answer:
            ratio = SequenceMatcher(
                None,
                " ".join(reference_answer.lower().split()),
                " ".join(response_text.lower().split()),
            ).ratio()
            score = ratio
            if ratio < 0.6:
                failure_reason = "low_similarity"
        else:
            score = 1.0

    return SimulationReport(
        dataset_id=dataset_id,
        expected_tool=expected_tool,
        tool_called=tool_called,
        called_tool=called_tool,
        arguments_valid=arguments_valid,
        score=score,
        response=tool_call.response,
        failure_reason=failure_reason,
    )
