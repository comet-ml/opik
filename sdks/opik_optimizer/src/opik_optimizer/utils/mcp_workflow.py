"""High-level helpers for MCP-powered demo scripts.

These utilities consolidate the boilerplate previously embedded in the
example scripts so that each demo can focus on the tooling configuration
instead of low-level orchestration details. The helpers are intentionally
generic so that any MCP tool can reuse them with minimal adjustment.
"""

from __future__ import annotations

import logging
import os
import time
import contextlib
import io
from contextvars import ContextVar
from dataclasses import dataclass, field
from typing import Any, Callable, Dict, Mapping, Optional

from opik.evaluation.metrics.score_result import ScoreResult

from .mcp import MCPManifest, call_tool_from_manifest, response_to_text
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
def _suppress_mcp_stdout(logger: logging.Logger):
    buffer = io.StringIO()
    with contextlib.redirect_stdout(buffer):
        yield
    for line in buffer.getvalue().splitlines():
        trimmed = line.strip()
        if not trimmed:
            continue
        if "MCP Server running on stdio" in trimmed:
            continue
        logger.debug("MCP stdout: %s", trimmed)


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
            with _suppress_mcp_stdout(self._logger):
                return call_tool_from_manifest(self.manifest, name, payload)

        prepared = dict(arguments)
        if self.argument_adapter:
            prepared = self.argument_adapter(prepared, call_tool)

        # TODO(opik-mcp): reuse a persistent MCP client so we avoid spawning a
        # new stdio subprocess for each call. This currently mirrors the
        # original blocking behaviour for stability.
        with _suppress_mcp_stdout(self._logger):
            response = call_tool(self.tool_name, prepared)
        text = response_to_text(response)
        preview = text[: self.preview_chars].replace("\n", " ")
        label = self.preview_label or self.tool_name
        self._logger.debug("MCP tool %s arguments=%s preview=%r", label, prepared, preview)

        summary = text
        if self.summary_builder is not None:
            summary = self.summary_builder(text, prepared)

        if self.summary_handler:
            self.summary_handler.record_summary(summary)

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
