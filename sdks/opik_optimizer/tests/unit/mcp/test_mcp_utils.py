import tests.unit.mcp.stub_opik  # noqa: F401
from pathlib import Path

import json
import logging
import sys
import textwrap
import types
from contextvars import ContextVar
from typing import Any, Callable, Dict, List, Mapping, Optional, Tuple

import pytest


root = Path(__file__).resolve().parents[3]
src_root = root / "src"

try:
    import opik_optimizer  # type: ignore  # noqa: F401
except ImportError:  # pragma: no cover - fallback for isolated tests
    pkg = types.ModuleType("opik_optimizer")
    pkg.__path__ = [str(src_root / "opik_optimizer")]
    sys.modules["opik_optimizer"] = pkg

if "opik_optimizer.utils" not in sys.modules:
    utils_pkg = types.ModuleType("opik_optimizer.utils")
    utils_pkg.__path__ = [str(src_root / "opik_optimizer" / "utils")]
    sys.modules["opik_optimizer.utils"] = utils_pkg

from opik_optimizer import ChatPrompt  # noqa: E402
from opik_optimizer.meta_prompt_optimizer.meta_prompt_optimizer import (  # noqa: E402
    _sync_tool_description_in_system,
)


from opik_optimizer.mcp_utils.mcp import (  # noqa: E402
    MCPManifest,
    ToolSignature,
    dump_mcp_signature,
    extract_description_from_system,
    load_mcp_signature,
    signature_updates,
    system_prompt_from_tool,
    tools_from_signatures,
    validate_tool_arguments,
)
from opik_optimizer.mcp_utils import mcp_workflow  # noqa: E402
from opik_optimizer.mcp_utils.mcp_workflow import (  # noqa: E402
    MCPToolInvocation,
    ensure_argument_via_resolver,
    extract_tool_arguments,
    make_argument_summary_builder,
    make_follow_up_builder,
    make_similarity_metric,
    preview_dataset_tool_invocation,
)
from opik_optimizer.mcp_utils.mcp_second_pass import MCPSecondPassCoordinator  # noqa: E402


def _sample_tool_entry() -> Dict[str, Any]:
    return {
        "type": "function",
        "function": {
            "name": "doc_lookup",
            "description": "Find documentation snippets.",
            "parameters": {
                "type": "object",
                "required": ["query"],
                "properties": {
                    "query": {"type": "string"},
                    "version": {"type": "string"},
                },
            },
            "examples": [
                {"arguments": {"query": "sdk install"}},
            ],
        },
    }


def test_load_and_dump_signature(tmp_path: Path) -> None:
    # Prepare a sample tool entry and write it as JSON to a file
    path = tmp_path / "signature.json"
    payload = [_sample_tool_entry()]
    path.write_text(json.dumps(payload))

    # Load the signature from the file
    signatures = load_mcp_signature(path)

    # Check that the loaded signature matches expectations
    assert len(signatures) == 1
    sig = signatures[0]
    assert sig.name == "doc_lookup"
    assert sig.description.startswith("Find documentation")
    assert sig.parameters is not None
    assert sig.parameters["type"] == "object"
    assert sig.examples is not None
    assert sig.examples[0]["arguments"]["query"] == "sdk install"

    # Dump the signature back to a new file and reload it to verify round-trip integrity
    out_path = tmp_path / "round_trip.json"
    dump_mcp_signature(signatures, out_path)
    reloaded = load_mcp_signature(out_path)
    assert reloaded[0].name == "doc_lookup"


def test_signature_updates_and_tool_conversion() -> None:
    signature = ToolSignature.from_tool_entry(_sample_tool_entry())
    updates = signature_updates([signature])
    assert updates == {"tool:doc_lookup": "Find documentation snippets."}

    tools = tools_from_signatures([signature])
    assert tools[0]["function"]["name"] == "doc_lookup"
    assert tools[0]["function"]["description"] == "Find documentation snippets."


def test_validate_tool_arguments() -> None:
    signature = ToolSignature.from_tool_entry(_sample_tool_entry())
    ok, message = validate_tool_arguments(signature, {"query": "install"})
    assert ok
    assert message == ""

    ok, message = validate_tool_arguments(signature, {})
    assert not ok
    assert "query" in message

    ok, message = validate_tool_arguments(signature, {"query": 123})
    assert not ok
    assert "string" in message


def test_system_prompt_generation_and_extraction() -> None:
    signature = ToolSignature.from_tool_entry(_sample_tool_entry())
    prompt = system_prompt_from_tool(signature)
    extracted = extract_description_from_system(prompt)
    assert signature.description in prompt
    assert extracted == signature.description


def test_extract_tool_arguments_handles_nested_dicts() -> None:
    item = {
        "input": {
            "arguments": {"query": "docs"},
        }
    }
    assert extract_tool_arguments(item) == {"query": "docs"}

    class Payload:
        input_values = {"arguments": {"topic": "api"}}

    assert extract_tool_arguments(Payload()) == {"topic": "api"}


def test_follow_up_builder_renders_summary_and_query() -> None:
    builder = make_follow_up_builder("Summary: {summary}. Question: {user_query}")
    follow_up = builder({"user_query": "How to install?"}, "Use the docs")
    assert follow_up is not None
    assert "Use the docs" in follow_up
    assert "How to install?" in follow_up


def test_make_argument_summary_builder_formats_arguments() -> None:
    builder = make_argument_summary_builder(
        heading="RESULT",
        instructions="Use the snippet",
        argument_labels={"id": "Identifier", "topic": "Topic"},
        preview_chars=10,
    )
    summary = builder("abcdefghijk", {"id": "lib", "topic": "routing"})
    assert "Identifier: lib" in summary
    assert "Topic: routing" in summary
    assert "abcdefghij" in summary  # preview truncated


def test_preview_dataset_tool_invocation(monkeypatch: pytest.MonkeyPatch) -> None:
    manifest = MCPManifest.from_dict(
        {
            "name": "stub",
            "command": "echo",
            "args": ["stub"],
            "env": {},
        }
    )

    class DummyDataset:
        def get_items(
            self, nb_samples: Optional[int] = None
        ) -> List[Dict[str, Any]]:  # pragma: no cover - signature parity
            return [{"arguments": {"query": "docs"}}]

    class DummyResponse:
        content = "tool output"

    captured: Dict[str, Any] = {}

    def fake_call(
        manifest_obj: MCPManifest, name: str, payload: Dict[str, Any]
    ) -> DummyResponse:
        captured["manifest"] = manifest_obj
        captured["name"] = name
        captured["payload"] = payload
        return DummyResponse()

    monkeypatch.setattr(mcp_workflow, "call_tool_from_manifest", fake_call)

    result = preview_dataset_tool_invocation(
        manifest=manifest,
        tool_name="doc_lookup",
        dataset=DummyDataset(),
    )

    assert result == "tool output"
    assert captured["manifest"] == manifest
    assert captured["name"] == "doc_lookup"
    assert captured["payload"] == {"query": "docs"}


def test_argument_adapter_resolves_missing_id(monkeypatch: pytest.MonkeyPatch) -> None:
    adapter = ensure_argument_via_resolver(
        target_field="context7CompatibleLibraryID",
        resolver_tool="resolver",
        query_fields=("library_query", "fallback_query"),
    )

    class Response:
        def __init__(self, text: str) -> None:
            self.content = [types.SimpleNamespace(text=text)]

    calls: List[Tuple[str, Dict[str, Any]]] = []

    def fake_call(name: str, payload: Dict[str, Any]) -> Any:
        calls.append((name, payload))
        return Response("/resolved/id")

    prepared = adapter({"library_query": "next"}, fake_call)
    assert prepared["context7CompatibleLibraryID"] == "/resolved/id"
    assert calls == [("resolver", {"query": "next"})]

    # When the target field already exists no resolver call should be made.
    prepared_noop = adapter(
        {"context7CompatibleLibraryID": "/existing", "library_query": "ignored"},
        fake_call,
    )
    assert prepared_noop["context7CompatibleLibraryID"] == "/existing"


def test_argument_adapter_skips_when_no_queries(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    adapter = ensure_argument_via_resolver(
        target_field="context7CompatibleLibraryID",
        resolver_tool="resolver",
        query_fields=("library_query",),
    )

    calls: List[Tuple[str, Dict[str, Any]]] = []

    def fake_call(name: str, payload: Dict[str, Any]) -> Any:
        calls.append((name, payload))
        raise AssertionError("Resolver should not be invoked")

    prepared = adapter({"topic": "routing"}, fake_call)
    assert "context7CompatibleLibraryID" not in prepared
    assert calls == []


def test_preview_dataset_tool_invocation_handles_empty_dataset(
    caplog: pytest.LogCaptureFixture, capsys: pytest.CaptureFixture[str]
) -> None:
    manifest = MCPManifest.from_dict(
        {
            "name": "stub",
            "command": "echo",
            "args": ["stub"],
            "env": {},
        }
    )

    class EmptyDataset:
        def get_items(
            self, nb_samples: Optional[int] = None
        ) -> List[Dict[str, Any]]:  # pragma: no cover - signature parity
            return []

    caplog.set_level(logging.WARNING, logger="opik_optimizer.mcp_utils.mcp_workflow")
    result = preview_dataset_tool_invocation(
        manifest=manifest,
        tool_name="doc_lookup",
        dataset=EmptyDataset(),
    )
    assert result is None
    out = capsys.readouterr().out.replace("\n", " ")
    assert "No dataset items available" in out
    assert "for preview." in out
    assert "preview." in out


def test_preview_dataset_tool_invocation_handles_errors(
    caplog: pytest.LogCaptureFixture, capsys: pytest.CaptureFixture[str]
) -> None:
    manifest = MCPManifest.from_dict(
        {
            "name": "stub",
            "command": "echo",
            "args": ["stub"],
            "env": {},
        }
    )

    class FailingDataset:
        def get_items(
            self, nb_samples: Optional[int] = None
        ) -> List[Dict[str, Any]]:  # pragma: no cover - signature parity
            raise RuntimeError("boom")

    caplog.set_level(logging.WARNING, logger="opik_optimizer.mcp_utils.mcp_workflow")
    result = preview_dataset_tool_invocation(
        manifest=manifest,
        tool_name="doc_lookup",
        dataset=FailingDataset(),
    )
    assert result is None
    out = capsys.readouterr().out.replace("\n", " ")
    assert "Failed to fetch dataset" in out
    assert "sample" in out
    assert "for preview:" in out


def test_system_prompt_masks_secrets_and_compacts_schema() -> None:
    signature = ToolSignature.from_tool_entry(_sample_tool_entry())
    manifest = MCPManifest.from_dict(
        {
            "name": "ctx",
            "command": "npx",
            "args": ["--api-key", "secret", "--flag", "value"],
            "env": {},
        }
    )
    prompt_text = system_prompt_from_tool(signature, manifest)
    assert "--api-key ***" in prompt_text
    assert "secret" not in prompt_text
    assert "Input Schema:\n{" in prompt_text


def test_mcp_tool_invocation_applies_adapter_and_records_summary(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    manifest = MCPManifest.from_dict(
        {
            "name": "ctx",
            "command": "echo",
            "args": ["stub"],
            "env": {},
        }
    )

    class FakeResponse:
        def __init__(self, text: str) -> None:
            self.content = [types.SimpleNamespace(text=text)]

    calls: List[Tuple[str, Dict[str, Any]]] = []

    def fake_call(
        manifest_obj: MCPManifest, tool_name: str, payload: Dict[str, Any]
    ) -> Any:
        calls.append((tool_name, payload))
        return FakeResponse(f"{tool_name}-result")

    monkeypatch.setattr(mcp_workflow, "call_tool_from_manifest", fake_call)

    recorded: List[str] = []

    class DummyCoordinator(MCPSecondPassCoordinator):
        def __init__(self) -> None:
            # Create a dummy follow-up builder
            def dummy_follow_up_builder(
                dataset_item: Dict[str, Any], summary: str
            ) -> Optional[str]:
                return None

            super().__init__(
                tool_name="dummy",
                summary_var=ContextVar[Optional[str]]("dummy_summary", default=None),
                follow_up_builder=dummy_follow_up_builder,
            )
            self._last_summary: Optional[str] = None
            self._last_follow_up: Optional[str] = None

        @property
        def tool_name(self) -> str:
            return self._tool_name

        def reset(self) -> None:
            self._summary_var.set(None)

        def record_summary(self, summary: str) -> None:
            recorded.append(summary)
            self._summary_var.set(summary)

        def fetch_summary(self) -> Optional[str]:
            return self._summary_var.get()

        def get_last_summary(self) -> Optional[str]:
            return self._last_summary

        def build_second_pass_messages(
            self,
            *,
            base_messages: List[Dict[str, Any]],
            dataset_item: Dict[str, Any],
            summary_override: Optional[str] = None,
        ) -> Optional[List[Dict[str, Any]]]:
            self._last_summary = None
            self._last_follow_up = None
            summary = (
                summary_override
                if summary_override is not None
                else self.fetch_summary()
            )
            if not summary:
                return None

            # Simple implementation that just returns the base messages
            self._last_summary = summary
            return base_messages

    def adapter(
        arguments: Dict[str, Any], call_tool: Callable[[str, Dict[str, Any]], Any]
    ) -> Dict[str, Any]:
        call_tool("resolver", {"query": arguments["library_query"]})
        arguments = dict(arguments)
        arguments["context7CompatibleLibraryID"] = "resolved"
        return arguments

    def summary_builder(tool_output: str, arguments: Mapping[str, Any]) -> str:
        return f"{tool_output}-{arguments['context7CompatibleLibraryID']}"

    invocation = MCPToolInvocation(
        manifest=manifest,
        tool_name="main",
        summary_handler=DummyCoordinator(),
        summary_builder=summary_builder,
        argument_adapter=adapter,
        rate_limit_sleep=0.0,
    )

    result = invocation.invoke({"library_query": "lookup"})

    assert result == "main-result-resolved"
    assert recorded == ["main-result-resolved"]
    assert calls == [
        ("resolver", {"query": "lookup"}),
        (
            "main",
            {"library_query": "lookup", "context7CompatibleLibraryID": "resolved"},
        ),
    ]


def test_make_similarity_metric_handles_missing_reference() -> None:
    metric = make_similarity_metric("context7")
    score = metric({"reference_answer": ""}, "output")
    assert score.value == 0.0
    assert "Missing reference" in score.reason


def test_sync_tool_description_updates_system_prompt() -> None:
    base_system = textwrap.dedent(
        """
        ### Available Tools
        - get-library-docs: old description

        Tool description:
        <<TOOL_DESCRIPTION>>
        old description
        <<END_TOOL_DESCRIPTION>>
        """
    ).strip()

    prompt = ChatPrompt(
        system=base_system,
        user="{question}",
        tools=[
            {
                "type": "function",
                "function": {
                    "name": "get-library-docs",
                    "description": "new description",
                    "parameters": {},
                },
            }
        ],
    )

    _sync_tool_description_in_system(prompt)

    assert prompt.system is not None
    assert prompt.system.count("new description") >= 2
    assert "old description" not in prompt.system
