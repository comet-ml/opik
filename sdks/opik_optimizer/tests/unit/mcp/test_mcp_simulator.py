from typing import Any
from collections.abc import Mapping

from opik_optimizer.datasets.context7_eval import load_context7_dataset
from opik_optimizer.mcp_utils.mcp import ToolSignature
from opik_optimizer.mcp_utils.mcp_simulator import (
    simulate_session,
    SimulationReport,
    ToolCallResult,
)


def _signature_for(name: str) -> ToolSignature:
    return ToolSignature(
        name=name,
        description="",
        parameters={
            "type": "object",
            "required": ["query"] if name == "doc_lookup" else ["url"],
            "properties": {
                "query": {"type": "string"},
                "url": {"type": "string"},
            },
        },
    )


def test_load_datasets_have_entries() -> None:
    context_examples = load_context7_dataset()
    assert len(context_examples.get_items()) >= 3


def test_simulate_session_default_invocation() -> None:
    dataset_item = {
        "id": "ctx-001",
        "expected_tool": "doc_lookup",
        "arguments": {"query": "auth"},
        "expected_answer_contains": "auth",
    }
    signature = _signature_for("doc_lookup")
    report = simulate_session({"doc_lookup": signature}, dataset_item)
    assert isinstance(report, SimulationReport)
    assert report.tool_called
    assert report.arguments_valid
    assert report.score == 1.0


def test_simulate_session_detects_argument_error() -> None:
    dataset_item = {
        "id": "ctx-err",
        "expected_tool": "doc_lookup",
        "arguments": {},
        "expected_answer_contains": "auth",
    }
    signature = _signature_for("doc_lookup")

    report = simulate_session({"doc_lookup": signature}, dataset_item)
    assert report.failure_reason is not None
    assert report.failure_reason.startswith("invalid_arguments")
    assert report.score == 0.0


def test_simulate_session_with_custom_invoker_detects_wrong_tool() -> None:
    dataset_item = {
        "id": "ctx-wrong",
        "expected_tool": "doc_lookup",
        "arguments": {"query": "auth"},
        "expected_answer_contains": "auth",
    }
    signature = _signature_for("doc_lookup")

    def bad_invoker(
        signature: ToolSignature,
        arguments: Mapping[str, Any],
        dataset_item: dict[str, Any],
    ) -> ToolCallResult:
        return ToolCallResult(
            tool_name="other_tool",
            arguments=arguments,
            response="",
        )

    report = simulate_session({"doc_lookup": signature}, dataset_item, bad_invoker)
    assert report.failure_reason == "wrong_tool"
