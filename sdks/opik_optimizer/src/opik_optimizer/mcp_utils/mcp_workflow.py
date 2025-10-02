"""High-level helpers for MCP-powered demo scripts.

These utilities consolidate the boilerplate previously embedded in the
example scripts so that each demo can focus on the tooling configuration
instead of low-level orchestration details. The helpers are intentionally
generic so that any MCP tool can reuse them with minimal adjustment.
"""

from __future__ import annotations

import contextlib
import copy
import io
import json
import logging
import os
import textwrap
import time
from contextvars import ContextVar
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any
from collections.abc import Callable, Iterator, Mapping, Sequence

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
from .mcp_second_pass import (
    MCPSecondPassCoordinator,
    FollowUpBuilder,
    extract_user_query,
)

logger = logging.getLogger(__name__)


ToolCall = Callable[[str, dict[str, Any]], Any]
ArgumentAdapter = Callable[[dict[str, Any], ToolCall], dict[str, Any]]
SummaryBuilder = Callable[[str, Mapping[str, Any]], str]
FallbackArgumentsProvider = Callable[[Any], dict[str, Any]]
FallbackInvoker = Callable[[dict[str, Any]], str]


def _default_rate_limit() -> float:
    value = os.getenv("MCP_RATELIMIT_SLEEP", "0.1")
    try:
        return float(value)
    except ValueError:
        logger.warning(
            "Invalid MCP_RATELIMIT_SLEEP=%r, expected a numeric value, using default 0.1",
            value,
        )
        return 0.1


DEFAULT_MCP_RATELIMIT_SLEEP = _default_rate_limit()


@contextlib.contextmanager
def suppress_mcp_stdout(logger: logging.Logger = logger) -> Iterator[None]:
    buffer = io.StringIO()
    with contextlib.redirect_stdout(buffer), contextlib.redirect_stderr(buffer):
        yield
    for line in buffer.getvalue().splitlines():
        trimmed = line.strip()
        if not trimmed:
            continue
        if (
            "MCP Server running on stdio" in trimmed
            or "Context7 Documentation MCP Server running on stdio" in trimmed
        ):
            continue
        logger.debug("MCP stdout: %s", trimmed)


def ensure_argument_via_resolver(
    *,
    target_field: str,
    resolver_tool: str,
    query_fields: Sequence[str],
) -> ArgumentAdapter:
    """Return an adapter that resolves ``target_field`` via an MCP tool."""

    def _adapter(arguments: dict[str, Any], call_tool: ToolCall) -> dict[str, Any]:
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


def extract_tool_arguments(item: Any) -> dict[str, Any]:
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
    summary_var_name: str | None = None,
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

    def _builder(dataset_item: dict[str, Any], summary: str) -> str | None:
        user_query = extract_user_query(dataset_item) or ""
        rendered = template.format(summary=summary, user_query=user_query).strip()
        return rendered or None

    return _builder


def make_similarity_metric(name: str) -> Callable[[dict[str, Any], str], ScoreResult]:
    """Return a Levenshtein-ratio style metric closure for demos."""

    def _metric(dataset_item: dict[str, Any], llm_output: str) -> ScoreResult:
        reference = (dataset_item.get("reference_answer") or "").strip()
        if not reference:
            return ScoreResult(
                name=f"{name}_similarity", value=0.0, reason="Missing reference answer."
            )

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
) -> ToolSignature:
    signature = load_tool_signature_from_manifest(manifest, tool_name)
    logger.debug("Loaded signature for %s", tool_name)
    return signature


def dump_signature_artifact(
    signature: ToolSignature,
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
) -> dict[str, Any]:
    tool_entry: dict[str, Any] = copy.deepcopy(dict(default_entry))
    prompt_tools = getattr(prompt, "tools", None)
    if prompt_tools:
        tool_entry = copy.deepcopy(dict(prompt_tools[0]))
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
    argument_adapter: ArgumentAdapter | None = None,
    resolver_manifest: MCPManifest | None = None,
    preview_chars: int = 200,
) -> str | None:
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

    def _resolver_call(name: str, payload: dict[str, Any]) -> Any:
        with suppress_mcp_stdout(logger):
            return call_tool_from_manifest(resolver_manifest, name, payload)

    prepared_args: dict[str, Any] = dict(sample_args)
    if argument_adapter:
        prepared_args = argument_adapter(sample_args, _resolver_call)

    return preview_tool_output(
        manifest,
        tool_name,
        prepared_args,
        logger=logger,
        preview_chars=preview_chars,
    )


def create_summary_var(name: str) -> ContextVar[str | None]:
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
    summary_handler: MCPSecondPassCoordinator | None = None
    summary_builder: SummaryBuilder | None = None
    argument_adapter: ArgumentAdapter | None = None
    preview_label: str | None = None
    preview_chars: int = 160
    rate_limit_sleep: float = DEFAULT_MCP_RATELIMIT_SLEEP
    cache_enabled: bool = True
    _logger: logging.Logger = field(default_factory=lambda: logger)
    _cache: dict[str, str] = field(default_factory=dict, init=False)

    def __call__(self, **arguments: Any) -> str:
        return self.invoke(arguments)

    def clear_cache(self) -> None:
        self._cache.clear()

    def invoke(
        self, arguments: Mapping[str, Any], *, use_cache: bool | None = None
    ) -> str:
        def call_tool(name: str, payload: dict[str, Any]) -> Any:
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

        effective_cache = self.cache_enabled if use_cache is None else use_cache
        cache_key: str | None = None
        if effective_cache:
            cache_key = self._make_cache_key(prepared)
            cached_summary = self._cache.get(cache_key)
            if cached_summary is not None:
                if self.summary_handler:
                    self.summary_handler.record_summary(cached_summary)
                self._logger.debug(
                    "MCP tool %s cache hit arguments=%s", self.tool_name, prepared
                )
                return cached_summary

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
        self._logger.debug(
            "MCP tool %s arguments=%s preview=%r", label, prepared, preview
        )

        summary = text
        if self.summary_builder is not None:
            summary = self.summary_builder(text, prepared)

        if self.summary_handler:
            self.summary_handler.record_summary(summary)

        if effective_cache and cache_key is not None:
            self._cache[cache_key] = summary

        if os.getenv("OPIK_DEBUG_MCP"):
            self._logger.info("MCP %s raw response:\n%s", label, text)

        return summary

    def _make_cache_key(self, payload: Mapping[str, Any]) -> str:
        try:
            return json.dumps(payload, sort_keys=True, default=str)
        except TypeError:
            normalised = self._normalise_cache_payload(payload)
            return json.dumps(normalised, sort_keys=True, default=str)

    @staticmethod
    def _normalise_cache_payload(value: Any) -> Any:
        if isinstance(value, Mapping):
            return {
                key: MCPToolInvocation._normalise_cache_payload(val)
                for key, val in sorted(value.items(), key=lambda item: str(item[0]))
            }
        if isinstance(value, list):
            return [MCPToolInvocation._normalise_cache_payload(item) for item in value]
        if isinstance(value, tuple):
            return [MCPToolInvocation._normalise_cache_payload(item) for item in value]
        if isinstance(value, set):
            return [
                MCPToolInvocation._normalise_cache_payload(item)
                for item in sorted(value, key=repr)
            ]
        if isinstance(value, (str, int, float, bool)) or value is None:
            return value
        return str(value)


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
    fallback_invoker: FallbackInvoker | None = None
    allow_tool_use_on_second_pass: bool = False


def preview_second_pass(
    prompt: Any,
    dataset_item: dict[str, Any],
    coordinator: MCPSecondPassCoordinator,
    agent_factory: Callable[[Any], Any],
    seed: int = 42,
) -> None:
    """Debug helper mirroring the old inline scripts."""

    coordinator.reset()
    agent = agent_factory(prompt)
    base_messages = prompt.get_messages(dataset_item)

    raw_output = agent.llm_invoke(
        messages=base_messages, seed=seed, allow_tool_use=True
    )
    logger.debug("Raw model output: %s", raw_output)

    second_pass_messages = coordinator.build_second_pass_messages(
        base_messages=base_messages,
        dataset_item=dataset_item,
    )

    if second_pass_messages:
        logger.debug("Second-pass messages: %s", second_pass_messages)
        final_output = agent.llm_invoke(
            messages=second_pass_messages,
            seed=seed,
            allow_tool_use=True,
        )
    else:
        final_output = raw_output

    logger.debug("Coerced final output: %s", final_output)
