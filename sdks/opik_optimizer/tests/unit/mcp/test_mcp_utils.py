import copy
import json
import logging
import textwrap
import types
from collections.abc import Callable, Mapping
from contextvars import ContextVar
from pathlib import Path
from typing import Any, Optional
from types import SimpleNamespace

import pytest

from opik_optimizer import ChatPrompt
from opik_optimizer.algorithms.evolutionary_optimizer import reporting as evo_reporting
from opik_optimizer.algorithms.evolutionary_optimizer.mcp import (
    EvolutionaryMCPContext,
    finalize_mcp_result,
    generate_tool_description_variations,
    tool_description_mutation,
)
from opik_optimizer.mcp_utils import mcp_workflow
from opik_optimizer.mcp_utils.mcp import (
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
from opik_optimizer.mcp_utils.mcp_second_pass import MCPSecondPassCoordinator
from opik_optimizer.mcp_utils.mcp_workflow import (
    MCPToolInvocation,
    ensure_argument_via_resolver,
    extract_tool_arguments,
    make_argument_summary_builder,
    make_follow_up_builder,
    make_similarity_metric,
    preview_dataset_tool_invocation,
)
from opik_optimizer.algorithms.meta_prompt_optimizer.meta_prompt_optimizer import (
    _sync_tool_description_in_system,
)
from opik_optimizer.optimization_result import OptimizationResult
import opik_optimizer


def _sample_tool_entry() -> dict[str, Any]:
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


def test_mcp_tool_invocation_cache_behaviour(monkeypatch: pytest.MonkeyPatch) -> None:
    manifest = MCPManifest.from_dict(
        {
            "name": "stub",
            "command": "echo",
            "args": ["stub"],
            "env": {},
        }
    )

    invocation = MCPToolInvocation(
        manifest=manifest,
        tool_name="doc_lookup",
        cache_enabled=True,
        preview_chars=10,
    )

    call_count = {"value": 0}

    def fake_call_tool(name: str, payload: dict[str, Any]) -> Any:
        call_count["value"] += 1
        return SimpleNamespace(content=f"result for {payload}")

    monkeypatch.setattr(
        mcp_workflow,
        "call_tool_from_manifest",
        lambda manifest_obj, name, payload: fake_call_tool(name, payload),
    )

    monkeypatch.setattr(
        mcp_workflow,
        "response_to_text",
        lambda response: response.content,
    )

    result1 = invocation.invoke({"query": "docs"})
    result2 = invocation.invoke({"query": "docs"})
    result3 = invocation.invoke({"query": "docs"}, use_cache=False)

    assert call_count["value"] == 2
    assert result1 == result2
    assert result3 == result1


def test_mcp_tool_invocation_cache_disabled(monkeypatch: pytest.MonkeyPatch) -> None:
    manifest = MCPManifest.from_dict(
        {
            "name": "stub",
            "command": "echo",
            "args": ["stub"],
            "env": {},
        }
    )

    invocation = MCPToolInvocation(
        manifest=manifest,
        tool_name="doc_lookup",
        cache_enabled=False,
    )

    call_count = {"value": 0}

    monkeypatch.setattr(
        mcp_workflow,
        "call_tool_from_manifest",
        lambda manifest_obj, name, payload: SimpleNamespace(
            content=f"payload={payload}"
        ),
    )

    monkeypatch.setattr(
        evo_reporting,
        "display_tool_description",
        lambda *args, **kwargs: None,
        raising=False,
    )

    def fake_converter(response: Any) -> str:
        call_count["value"] += 1
        return response.content

    monkeypatch.setattr(mcp_workflow, "response_to_text", fake_converter)

    invocation.invoke({"query": "docs"})
    invocation.invoke({"query": "docs"})
    invocation.clear_cache()

    assert call_count["value"] == 2


def test_generate_tool_description_variations(monkeypatch: pytest.MonkeyPatch) -> None:
    tool_entry = _sample_tool_entry()
    prompt = ChatPrompt(
        system=(
            "Instruction\n<<TOOL_DESCRIPTION>>Find documentation snippets."
            "<<END_TOOL_DESCRIPTION>>"
        ),
        user="{query}",
        tools=[copy.deepcopy(tool_entry)],
    )

    context = EvolutionaryMCPContext(
        tool_name="doc_lookup",
        tool_segment_id="tool:doc_lookup",
        original_description=tool_entry["function"]["description"],
        tool_metadata=tool_entry,
        panel_style="green",
    )

    monkeypatch.setattr(
        evo_reporting, "display_tool_description", lambda *args, **kwargs: None
    )

    monkeypatch.setattr(
        opik_optimizer._llm_calls,
        "call_model",
        lambda *args, **kwargs: json.dumps(
            {
                "prompts": [
                    {
                        "tool_description": "Refined documentation helper.",
                        "improvement_focus": "clarity",
                    }
                ]
            }
        ),
    )

    candidates = generate_tool_description_variations(
        base_prompt=prompt,
        context=context,
        num_variations=2,
        model="openai/gpt-4o-mini",
        model_parameters={},
        optimization_id="opt-id",
    )
    assert len(candidates) == 1
    candidate = candidates[0]
    assert candidate.tools is not None
    assert (
        candidate.tools[0]["function"]["description"] == "Refined documentation helper."
    )
    assert candidate.system is not None
    assert "Refined documentation helper." in candidate.system


def test_tool_description_mutation_and_finalize(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    tool_entry = _sample_tool_entry()
    prompt = ChatPrompt(
        system=(
            "Instruction\n<<TOOL_DESCRIPTION>>Find documentation snippets."
            "<<END_TOOL_DESCRIPTION>>"
        ),
        user="{query}",
        tools=[copy.deepcopy(tool_entry)],
    )

    context = EvolutionaryMCPContext(
        tool_name="doc_lookup",
        tool_segment_id="tool:doc_lookup",
        original_description=tool_entry["function"]["description"],
        tool_metadata=tool_entry,
        panel_style="green",
    )

    monkeypatch.setattr(
        evo_reporting, "display_tool_description", lambda *args, **kwargs: None
    )

    monkeypatch.setattr(
        opik_optimizer._llm_calls,
        "call_model",
        lambda *args, **kwargs: json.dumps(
            {
                "prompts": [
                    {
                        "tool_description": "Updated documentation helper.",
                        "improvement_focus": "coverage",
                    }
                ]
            }
        ),
    )

    mutated = tool_description_mutation(
        prompt=prompt,
        context=context,
        model="openai/gpt-4o-mini",
        model_parameters={},
        optimization_id="opt-id",
    )
    assert mutated is not None
    assert mutated.tools is not None
    assert (
        mutated.tools[0]["function"]["description"] == "Updated documentation helper."
    )

    result = OptimizationResult(
        optimizer="EvolutionaryOptimizer",
        prompt=mutated.get_messages(),
        score=0.5,
        initial_prompt=prompt.get_messages(),
        initial_score=0.4,
        metric_name="dummy_metric",
        details={"final_tools": mutated.tools},
    )

    finalize_mcp_result(result, context, "green")
    assert result.tool_prompts is not None
    assert result.tool_prompts["doc_lookup"] == "Updated documentation helper."

    fallback_result = OptimizationResult(
        optimizer="EvolutionaryOptimizer",
        prompt=prompt.get_messages(),
        score=0.1,
        initial_prompt=prompt.get_messages(),
        initial_score=0.1,
        metric_name="dummy_metric",
        details={},
    )

    finalize_mcp_result(fallback_result, context, "green")
    assert fallback_result.tool_prompts is not None
    assert fallback_result.tool_prompts["doc_lookup"] == context.original_description


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
            self, nb_samples: int | None = None
        ) -> list[dict[str, Any]]:  # pragma: no cover - signature parity
            return [{"arguments": {"query": "docs"}}]

    class DummyResponse:
        content = "tool output"

    captured: dict[str, Any] = {}

    def fake_call(
        manifest_obj: MCPManifest, name: str, payload: dict[str, Any]
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

    calls: list[tuple[str, dict[str, Any]]] = []

    def fake_call(name: str, payload: dict[str, Any]) -> Any:
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

    calls: list[tuple[str, dict[str, Any]]] = []

    def fake_call(name: str, payload: dict[str, Any]) -> Any:
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
            self, nb_samples: int | None = None
        ) -> list[dict[str, Any]]:  # pragma: no cover - signature parity
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
            self, nb_samples: int | None = None
        ) -> list[dict[str, Any]]:  # pragma: no cover - signature parity
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

    calls: list[tuple[str, dict[str, Any]]] = []

    def fake_call(
        manifest_obj: MCPManifest, tool_name: str, payload: dict[str, Any]
    ) -> Any:
        calls.append((tool_name, payload))
        return FakeResponse(f"{tool_name}-result")

    monkeypatch.setattr(mcp_workflow, "call_tool_from_manifest", fake_call)

    recorded: list[str] = []

    class DummyCoordinator(MCPSecondPassCoordinator):
        def __init__(self) -> None:
            # Create a dummy follow-up builder
            def dummy_follow_up_builder(
                dataset_item: dict[str, Any], summary: str
            ) -> str | None:
                return None

            super().__init__(
                tool_name="dummy",
                summary_var=ContextVar[Optional[str]]("dummy_summary", default=None),
                follow_up_builder=dummy_follow_up_builder,
            )
            self._last_summary: str | None = None
            self._last_follow_up: str | None = None

        @property
        def tool_name(self) -> str:
            return self._tool_name

        def reset(self) -> None:
            self._summary_var.set(None)

        def record_summary(self, summary: str) -> None:
            recorded.append(summary)
            self._summary_var.set(summary)

        def fetch_summary(self) -> str | None:
            return self._summary_var.get()

        def get_last_summary(self) -> str | None:
            return self._last_summary

        def build_second_pass_messages(
            self,
            *,
            base_messages: list[dict[str, Any]],
            dataset_item: dict[str, Any],
            summary_override: str | None = None,
        ) -> list[dict[str, Any]] | None:
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
        arguments: dict[str, Any], call_tool: Callable[[str, dict[str, Any]], Any]
    ) -> dict[str, Any]:
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
