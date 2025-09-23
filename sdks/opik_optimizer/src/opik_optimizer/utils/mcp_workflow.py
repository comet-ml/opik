"""High-level helpers for MCP-powered demo scripts.

These utilities consolidate the boilerplate previously embedded in the
example scripts so that each demo can focus on the tooling configuration
instead of low-level orchestration details. The helpers are intentionally
generic so that any MCP tool can reuse them with minimal adjustment.
"""

from __future__ import annotations

import contextlib
import io
import logging
import os
import textwrap
import time
from contextvars import ContextVar
from dataclasses import dataclass, field
import copy
from pathlib import Path
from typing import Any, Callable, Dict, Iterable, Mapping, Optional, Sequence

from opik import track
from opik.evaluation.metrics.score_result import ScoreResult

from .mcp import (
    MCPManifest,
    ToolSignature,
    call_tool_from_manifest,
    dump_mcp_signature,
    list_tools_from_manifest,
    load_tool_signature_from_manifest,
    response_to_text,
)
from .mcp_second_pass import MCPSecondPassCoordinator, FollowUpBuilder, extract_user_query

logger = logging.getLogger(__name__)


ToolCall = Callable[[str, Dict[str, Any]], Any]
ArgumentAdapter = Callable[[Dict[str, Any], ToolCall], Dict[str, Any]]
SummaryBuilder = Callable[[str, Mapping[str, Any]], str]
FallbackArgumentsProvider = Callable[[Any], Dict[str, Any]]
FallbackInvoker = Callable[[Dict[str, Any]], str]


def _default_rate_limit() -> float:
    value = os.getenv("MCP_RATELIMIT_SLEEP", "0.1")
    try:
        return float(value)
    except ValueError:
        logger.warning("Invalid MCP_RATELIMIT_SLEEP=%r, using 0.1", value)
        return 0.1


DEFAULT_MCP_RATELIMIT_SLEEP = _default_rate_limit()


@contextlib.contextmanager
def suppress_mcp_stdout(logger: logging.Logger = logger):
    buffer = io.StringIO()
    with contextlib.redirect_stdout(buffer), contextlib.redirect_stderr(buffer):
        yield
    for line in buffer.getvalue().splitlines():
        trimmed = line.strip()
        if not trimmed:
            continue
        if "MCP Server running on stdio" in trimmed:
            continue
        logger.debug("MCP stdout: %s", trimmed)


def ensure_argument_via_resolver(
    *,
    target_field: str,
    resolver_tool: str,
    query_fields: Sequence[str],
) -> ArgumentAdapter:
    """Return an adapter that resolves ``target_field`` via an MCP tool."""

    def _adapter(arguments: Dict[str, Any], call_tool: ToolCall) -> Dict[str, Any]:
        prepared = dict(arguments)
        if prepared.get(target_field):
            return prepared
        for key in query_fields:
            query = prepared.get(key)
            if not query:
                continue
            response = call_tool(resolver_tool, {"query": query})
            resolved = response_to_text(response).strip()
            if resolved:
                prepared[target_field] = resolved
                break
        return prepared

    return _adapter


def extract_tool_arguments(item: Any) -> Dict[str, Any]:
    """Best-effort extraction of tool arguments from dataset records.

    The helper understands the common structures we use in tests and
    examples but stays permissive so it keeps working with future
    dataset variants.
    """

    if isinstance(item, dict):
        if "arguments" in item and isinstance(item["arguments"], dict):
            return dict(item["arguments"])
        if "input" in item and isinstance(item["input"], dict):
            arguments = item["input"].get("arguments")
            if isinstance(arguments, dict):
                return dict(arguments)

    for attr in ("input_values", "input", "data"):
        value = getattr(item, attr, None)
        if isinstance(value, dict):
            arguments = value.get("arguments")
            if isinstance(arguments, dict):
                return dict(arguments)

    return {}


def create_second_pass_coordinator(
    tool_name: str,
    follow_up_template: str,
    *,
    summary_var_name: Optional[str] = None,
) -> MCPSecondPassCoordinator:
    summary_var = create_summary_var(summary_var_name or f"{tool_name}_summary")
    follow_up_builder = make_follow_up_builder(follow_up_template)
    return MCPSecondPassCoordinator(
        tool_name=tool_name,
        summary_var=summary_var,
        follow_up_builder=follow_up_builder,
    )


def make_follow_up_builder(template: str) -> FollowUpBuilder:
    """Create a ``FollowUpBuilder`` that fills a string template.

    The template receives ``summary`` and ``user_query`` keyword
    arguments. Missing user queries collapse to an empty string so the
    template can stay simple (e.g. ``"Use the summary: {summary}"``).
    """

    def _builder(dataset_item: Dict[str, Any], summary: str) -> Optional[str]:
        user_query = extract_user_query(dataset_item) or ""
        rendered = template.format(summary=summary, user_query=user_query).strip()
        return rendered or None

    return _builder


def make_similarity_metric(name: str) -> Callable[[Dict[str, Any], str], ScoreResult]:
    """Return a Levenshtein-ratio style metric closure for demos."""

    def _metric(dataset_item: Dict[str, Any], llm_output: str) -> ScoreResult:
        reference = (dataset_item.get("reference_answer") or "").strip()
        if not reference:
            return ScoreResult(name=f"{name}_similarity", value=0.0, reason="Missing reference answer.")

        def _normalize(text: str) -> str:
            return " ".join(text.lower().split())

        ratio = _sequence_match_ratio(_normalize(reference), _normalize(llm_output))
        reason = f"Levenshtein ratio {ratio:.2f} against reference."
        return ScoreResult(
            name=f"{name}_similarity",
            value=ratio,
            reason=reason,
            metadata={"reference": reference},
        )

    return _metric


def _sequence_match_ratio(a: str, b: str) -> float:
    """Local wrapper to avoid importing difflib in several modules."""

    from difflib import SequenceMatcher

    return SequenceMatcher(None, a, b).ratio()


def list_manifest_tools(
    manifest: MCPManifest, *, logger: logging.Logger = logger
) -> tuple[list[Any], list[str]]:
    with suppress_mcp_stdout(logger):
        tools = list_tools_from_manifest(manifest)
    names = [getattr(tool, "name", "") for tool in tools if getattr(tool, "name", None)]
    logger.info("MCP tools available: %s", names)
    return tools, names


def load_manifest_tool_signature(
    manifest: MCPManifest,
    tool_name: str,
    *,
    logger: logging.Logger = logger,
) -> 'ToolSignature':
    signature = load_tool_signature_from_manifest(manifest, tool_name)
    logger.debug("Loaded signature for %s", tool_name)
    return signature


def dump_signature_artifact(
    signature: 'ToolSignature',
    artifacts_dir: Path | str,
    filename: str,
    *,
    logger: logging.Logger = logger,
) -> Path:
    artifacts_path = Path(artifacts_dir)
    artifacts_path.mkdir(parents=True, exist_ok=True)
    destination = artifacts_path / filename
    dump_mcp_signature([signature], destination)
    logger.info("Signature written to %s", destination)
    return destination


def update_signature_from_tool_entry(
    signature: ToolSignature, tool_entry: Mapping[str, Any]
) -> ToolSignature:
    function_block = tool_entry.get("function", {})
    signature.description = function_block.get("description", signature.description)
    signature.parameters = function_block.get("parameters", signature.parameters)
    signature.examples = function_block.get("examples", signature.examples)
    signature.extra = {
        **signature.extra,
        **{k: v for k, v in tool_entry.items() if k != "function"},
    }
    return signature


def apply_tool_entry_from_prompt(
    signature: ToolSignature,
    prompt: Any,
    default_entry: Mapping[str, Any],
) -> Dict[str, Any]:
    tool_entry = copy.deepcopy(default_entry)
    prompt_tools = getattr(prompt, "tools", None)
    if prompt_tools:
        tool_entry = copy.deepcopy(prompt_tools[0])
    update_signature_from_tool_entry(signature, tool_entry)
    return tool_entry


def preview_tool_output(
    manifest: MCPManifest,
    tool_name: str,
    arguments: Mapping[str, Any],
    *,
    logger: logging.Logger = logger,
    preview_chars: int = 200,
) -> str:
    with suppress_mcp_stdout(logger):
        response = call_tool_from_manifest(manifest, tool_name, dict(arguments))
    text = response_to_text(response)
    preview = text[:preview_chars].replace("\n", " ")
    logger.info("Sample tool output preview: %s", preview)
    return text


def preview_dataset_tool_invocation(
    *,
    manifest: MCPManifest,
    tool_name: str,
    dataset: Any,
    logger: logging.Logger = logger,
    argument_adapter: Optional[ArgumentAdapter] = None,
    resolver_manifest: Optional[MCPManifest] = None,
    preview_chars: int = 200,
) -> Optional[str]:
    """Execute a best-effort preview tool call using a dataset sample."""

    resolver_manifest = resolver_manifest or manifest

    try:
        items = dataset.get_items(nb_samples=1)
    except Exception as exc:  # pragma: no cover - defensive logging
        logger.warning("Failed to fetch dataset sample for preview: %s", exc)
        return None

    if not items:
        logger.warning("No dataset items available for preview.")
        return None

    sample_item = items[0]
    sample_args = extract_tool_arguments(sample_item)
    if not sample_args:
        logger.warning("No sample arguments available for preview.")
        return None

    def _resolver_call(name: str, payload: Dict[str, Any]) -> Any:
        with suppress_mcp_stdout(logger):
            return call_tool_from_manifest(resolver_manifest, name, payload)

    prepared_args = sample_args
    if argument_adapter:
        prepared_args = argument_adapter(sample_args, _resolver_call)

    return preview_tool_output(
        manifest,
        tool_name,
        prepared_args,
        logger=logger,
        preview_chars=preview_chars,
    )


def create_summary_var(name: str) -> ContextVar[Optional[str]]:
    """Return a ``ContextVar`` used to share tool summaries."""

    return ContextVar(name, default=None)


@dataclass
class MCPToolInvocation:
    """Callable helper for invoking MCP tools with consistent logging.

    A single instance can be registered in a ``ChatPrompt`` function map
    while keeping the script in charge of manifest, summary handling and
    optional argument adaptation.
    """

    manifest: MCPManifest
    tool_name: str
    summary_handler: Optional[MCPSecondPassCoordinator] = None
    summary_builder: Optional[SummaryBuilder] = None
    argument_adapter: Optional[ArgumentAdapter] = None
    preview_label: Optional[str] = None
    preview_chars: int = 160
    rate_limit_sleep: float = DEFAULT_MCP_RATELIMIT_SLEEP
    _logger: logging.Logger = field(default_factory=lambda: logger)

    def __call__(self, **arguments: Any) -> str:
        return self.invoke(arguments)

    def invoke(self, arguments: Mapping[str, Any]) -> str:
        def call_tool(name: str, payload: Dict[str, Any]) -> Any:
            if self.rate_limit_sleep > 0:
                time.sleep(self.rate_limit_sleep)
            with suppress_mcp_stdout(self._logger):
                @track(name=f"mcp_tool::{name}")
                def _tracked() -> Any:
                    return call_tool_from_manifest(self.manifest, name, payload)

                return _tracked()

        prepared = dict(arguments)
        if self.argument_adapter:
            prepared = self.argument_adapter(prepared, call_tool)

        # TODO(opik-mcp): reuse a persistent MCP client so we avoid spawning a
        # new stdio subprocess for each call. This currently mirrors the
        # original blocking behaviour for stability.
        with suppress_mcp_stdout(self._logger):
            @track(name=f"mcp_tool::{self.tool_name}")
            def _invoke() -> Any:
                return call_tool(self.tool_name, prepared)

            response = _invoke()
        text = response_to_text(response)
        preview = text[: self.preview_chars].replace("\n", " ")
        label = self.preview_label or self.tool_name
        self._logger.debug("MCP tool %s arguments=%s preview=%r", label, prepared, preview)

        summary = text
        if self.summary_builder is not None:
            summary = self.summary_builder(text, prepared)

        if self.summary_handler:
            self.summary_handler.record_summary(summary)

        if os.getenv("OPIK_DEBUG_MCP"):
            self._logger.info("MCP %s raw response:\n%s", label, text)

        return summary


def summarise_with_template(template: str) -> SummaryBuilder:
    """Return a summary builder that fills the provided template."""

    def _builder(tool_output: str, arguments: Mapping[str, Any]) -> str:
        return template.format(response=tool_output, arguments=dict(arguments))

    return _builder


def default_summary_builder(label: str, instructions: str) -> SummaryBuilder:
    """Convenience factory for the demos' structured summaries."""

    template = (
        "{label}\n"
        "Arguments: {{arguments}}\n"
        "Instructions: {instructions}\n"
        "Response Preview:\n"
        "{{response}}"
    ).format(label=label, instructions=instructions)

    return summarise_with_template(template)


def make_argument_summary_builder(
    *,
    heading: str,
    instructions: str,
    argument_labels: Mapping[str, str],
    preview_chars: int = 800,
) -> SummaryBuilder:
    """Return a structured summary builder that highlights selected arguments."""

    def _builder(tool_output: str, arguments: Mapping[str, Any]) -> str:
        scoped_args = dict(arguments)
        highlighted = "\n".join(
            f"{label}: {scoped_args.get(key, 'unknown')}"
            for key, label in argument_labels.items()
        )
        snippet = tool_output[:preview_chars]
        return textwrap.dedent(
            f"""
            {heading}
            {highlighted}
            Instructions: {instructions}
            Documentation Snippet:
            {snippet}
            """
        ).strip()

    return _builder


@dataclass
class MCPExecutionConfig:
    """Container describing how to run MCP-aware evaluations."""

    coordinator: MCPSecondPassCoordinator
    tool_name: str
    fallback_arguments: FallbackArgumentsProvider = extract_tool_arguments
    fallback_invoker: Optional[FallbackInvoker] = None
    allow_tool_use_on_second_pass: bool = False


def preview_second_pass(
    prompt,
    dataset_item: Dict[str, Any],
    coordinator: MCPSecondPassCoordinator,
    agent_factory: Callable[[Any], Any],
) -> None:
    """Debug helper mirroring the old inline scripts."""

    coordinator.reset()
    agent = agent_factory(prompt)
    base_messages = prompt.get_messages(dataset_item)

    raw_output = agent.llm_invoke(messages=base_messages, seed=42, allow_tool_use=True)
    logger.debug("Raw model output: %s", raw_output)

    second_pass_messages = coordinator.build_second_pass_messages(
        base_messages=base_messages,
        dataset_item=dataset_item,
    )

    if second_pass_messages:
        logger.debug("Second-pass messages: %s", second_pass_messages)
        final_output = agent.llm_invoke(
            messages=second_pass_messages,
            seed=101,
            allow_tool_use=True,
        )
    else:
        final_output = raw_output

    logger.debug("Coerced final output: %s", final_output)
