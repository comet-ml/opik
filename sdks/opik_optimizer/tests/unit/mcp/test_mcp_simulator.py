from . import stub_opik  # noqa: F401
import importlib
import sys
import types
from pathlib import Path
from typing import TYPE_CHECKING, Any


root = Path(__file__).resolve().parents[3]
src_root = root / "src"

if "opik_optimizer" not in sys.modules:
    pkg = types.ModuleType("opik_optimizer")
    pkg.__path__ = [str(src_root / "opik_optimizer")]
    sys.modules["opik_optimizer"] = pkg

if "opik_optimizer.datasets" not in sys.modules:
    datasets_pkg = types.ModuleType("opik_optimizer.datasets")
    datasets_pkg.__path__ = [str(src_root / "opik_optimizer" / "datasets")]
    sys.modules["opik_optimizer.datasets"] = datasets_pkg

if "opik_optimizer.utils" not in sys.modules:
    utils_pkg = types.ModuleType("opik_optimizer.utils")
    utils_pkg.__path__ = [str(src_root / "opik_optimizer" / "utils")]
    sys.modules["opik_optimizer.utils"] = utils_pkg


context_dataset_module = importlib.import_module(
    "opik_optimizer.datasets.context7_eval"
)
mcp_module = importlib.import_module("opik_optimizer.mcp_utils.mcp")
mcp_simulator_module = importlib.import_module("opik_optimizer.mcp_utils.mcp_simulator")

load_context7_dataset = context_dataset_module.load_context7_dataset
simulate_session = mcp_simulator_module.simulate_session
if TYPE_CHECKING:
    from opik_optimizer.mcp_utils.mcp import ToolSignature as MCPToolSignature
    from opik_optimizer.mcp_utils.mcp_simulator import (
        ToolCallResult as MCPToolCallResult,
    )
else:  # pragma: no cover - typing fallback
    MCPToolSignature = Any  # type: ignore[valid-type]
    MCPToolCallResult = Any  # type: ignore[valid-type]


def _signature_for(name: str) -> MCPToolSignature:
    return mcp_module.ToolSignature(
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
    assert isinstance(report, mcp_simulator_module.SimulationReport)
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
        signature: MCPToolSignature,
        arguments: dict[str, Any],
        dataset_item: dict[str, Any],
    ) -> MCPToolCallResult:
        return mcp_simulator_module.ToolCallResult(
            tool_name="other_tool",
            arguments=arguments,
            response="",
        )

    report = simulate_session({"doc_lookup": signature}, dataset_item, bad_invoker)
    assert report.failure_reason == "wrong_tool"
