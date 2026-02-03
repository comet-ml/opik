"""Shared helpers for tool description optimization across optimizers."""

from __future__ import annotations

import json
import logging
import warnings
from typing import Any
from collections.abc import Callable

from pydantic import BaseModel, Field
from litellm.exceptions import BadRequestError

from ...api_objects import chat_prompt
from ...api_objects.types import MetricFunction
from ...utils import prompt_segments
from ... import _llm_calls, reporting_utils
from ...algorithms.meta_prompt_optimizer import prompts as meta_prompts
from ...algorithms.meta_prompt_optimizer import reporting
from ...algorithms.meta_prompt_optimizer.ops import (
    candidate_ops as meta_candidate_ops,
)
from .tool_factory import ToolCallingFactory

logger = logging.getLogger(__name__)

ToolDescriptionReporter = Callable[[str, str, dict[str, Any]], None]


def resolve_prompt_tools(
    prompt_or_prompts: chat_prompt.ChatPrompt | dict[str, chat_prompt.ChatPrompt],
    *,
    optimize_tools: bool | dict[str, bool] | None = None,
) -> tuple[
    chat_prompt.ChatPrompt | dict[str, chat_prompt.ChatPrompt], list[str] | None
]:
    """Normalize toolcalling entries in ChatPrompt(s).

    Args:
        prompt_or_prompts: Single ChatPrompt or mapping of ChatPrompts.
        optimize_tools: Optional tool optimization selector (single-prompt only).

    Returns:
        Tuple of (normalized prompt(s), tool_names for optimization or None).
    """
    if isinstance(prompt_or_prompts, dict):
        if optimize_tools:
            raise ValueError(
                "Tool description optimization only supports single prompts."
            )
        factory = ToolCallingFactory()
        resolved = {
            name: factory.resolve_prompt(prompt)
            for name, prompt in prompt_or_prompts.items()
        }
        return resolved, None

    if optimize_tools:
        return prepare_tool_optimization(prompt_or_prompts, optimize_tools)

    resolved_prompt = ToolCallingFactory().resolve_prompt(prompt_or_prompts)
    return resolved_prompt, None


def should_allow_tool_use(
    prompts: dict[str, chat_prompt.ChatPrompt],
) -> bool:
    """Return whether tool use should be enabled for this prompt bundle."""
    allow_flags: list[bool] = []
    for prompt in prompts.values():
        if not prompt.tools:
            continue
        allow_flag = True
        if isinstance(prompt.model_kwargs, dict):
            allow_flag = bool(prompt.model_kwargs.get("allow_tool_use", True))
        allow_flags.append(allow_flag)
    return any(allow_flags)


def validate_optimization_flags(
    *,
    optimize_prompt: bool,
    optimize_tools: bool | dict[str, bool] | None,
    supports_tool_optimization: bool,
    warn_unsupported: bool = False,
) -> bool | dict[str, bool] | None:
    """Validate optimization flags and normalize tool optimization options."""
    if not optimize_prompt and not optimize_tools:
        raise ValueError("optimize_prompt and optimize_tools are both disabled.")

    if optimize_tools and not supports_tool_optimization:
        if not optimize_prompt:
            raise ValueError(
                "Tool-only optimization is not supported by this optimizer."
            )
        if warn_unsupported:
            warnings.warn(
                "optimize_tools is not supported by this optimizer; ignoring tool optimization.",
                UserWarning,
                stacklevel=2,
            )
        return None

    return optimize_tools


class ToolDescriptionCandidate(BaseModel):
    """Response model for a tool description candidate."""

    tool_descriptions: dict[str, str] = Field(
        ..., description="Updated tool descriptions keyed by tool name"
    )
    parameter_descriptions: dict[str, dict[str, str]] | None = Field(
        None,
        description="Updated parameter descriptions keyed by tool and parameter name",
    )
    improvement_focus: str | None = Field(
        None, description="What aspect the description improves"
    )
    reasoning: str | None = Field(
        None, description="Explanation for the updated description"
    )


class ToolDescriptionCandidatesResponse(BaseModel):
    """Response model for tool description candidate generation."""

    prompts: list[ToolDescriptionCandidate] = Field(
        ..., description="List of tool description candidates"
    )


def prepare_tool_optimization(
    prompt: chat_prompt.ChatPrompt,
    optimize_tools: bool | dict[str, bool] | None,
) -> tuple[chat_prompt.ChatPrompt, list[str] | None]:
    """Resolve MCP tools and return selected tool names for optimization."""
    if not optimize_tools:
        return prompt, None

    resolved_prompt = ToolCallingFactory().resolve_prompt(prompt)
    if not resolved_prompt.tools:
        raise ValueError("Prompt must include tools for tool description optimization.")

    segments = prompt_segments.extract_prompt_segments(resolved_prompt)
    tool_segments = [segment for segment in segments if segment.is_tool()]
    if not tool_segments:
        raise ValueError("Prompt tools are missing tool descriptions to optimize.")

    tool_names: list[str] | None = None
    if isinstance(optimize_tools, dict):
        tool_names = [name for name, enabled in optimize_tools.items() if enabled]
        if not tool_names:
            raise ValueError("optimize_tools dict did not enable any tools.")
        available = {segment.segment_id for segment in tool_segments}
        missing = [
            name
            for name in tool_names
            if f"{prompt_segments.PROMPT_SEGMENT_PREFIX_TOOL}{name}" not in available
        ]
        if missing:
            raise ValueError(
                f"Tools not found in prompt: {missing}. Available: {sorted(available)}"
            )
    elif optimize_tools is True:
        tool_names = [
            segment.segment_id.replace(
                prompt_segments.PROMPT_SEGMENT_PREFIX_TOOL, "", 1
            )
            for segment in tool_segments
        ]
    return resolved_prompt, tool_names


def generate_tool_description_candidates(
    optimizer: Any,
    current_prompt: chat_prompt.ChatPrompt,
    best_score: float,
    round_num: int,
    previous_rounds: list[Any],
    metric: MetricFunction,
    build_history_context_fn: Callable,
    tool_names: list[str] | None = None,
    optimization_id: str | None = None,
    project_name: str | None = None,
    tool_description_reporter: ToolDescriptionReporter | None = None,
) -> list[chat_prompt.ChatPrompt]:
    """Generate candidate prompts that only update tool descriptions."""
    with reporting.display_candidate_generation_report(
        optimizer.prompts_per_round,
        verbose=optimizer.verbose,
        selection_summary=reporting_utils.summarize_selection_policy(current_prompt),
    ) as candidate_generation_report:
        metric_name = getattr(metric, "__name__", "metric")
        logger.debug(
            "\nGenerating tool descriptions for round %s (score=%.4f, metric=%s)",
            round_num + 1,
            best_score,
            metric_name,
        )

        tool_segments = _resolve_tool_segments(current_prompt, tool_names)
        if tool_description_reporter:
            for segment in tool_segments:
                tool_name = segment.segment_id.replace(
                    prompt_segments.PROMPT_SEGMENT_PREFIX_TOOL, "", 1
                )
                tool_description_reporter(segment.content, tool_name, segment.metadata)
        history_context = build_history_context_fn(previous_rounds)
        tool_blocks_str = _build_tool_blocks(tool_segments)

        tool_description_user_template = optimizer.get_prompt("tool_description_user")
        tool_description_system_template = optimizer.get_prompt(
            "tool_description_system"
        )
        user_prompt = meta_prompts.build_tool_description_user_prompt(
            tool_blocks=tool_blocks_str,
            best_score=best_score,
            history_context=history_context,
            prompts_per_round=optimizer.prompts_per_round,
            template=tool_description_user_template,
        )

        try:
            metadata_for_call = meta_candidate_ops._build_metadata_for_call(
                optimizer=optimizer,
                call_type="optimization_algorithm",
                optimization_id=optimization_id,
                project_name=project_name,
            )

            response = _llm_calls.call_model(
                messages=[
                    {
                        "role": "system",
                        "content": meta_prompts.build_tool_description_system_prompt(
                            template=tool_description_system_template
                        ),
                    },
                    {"role": "user", "content": user_prompt},
                ],
                model=optimizer.model,
                model_parameters=optimizer.model_parameters,
                metadata=metadata_for_call,
                optimization_id=optimization_id,
                project_name=project_name,
                return_all=_llm_calls.requested_multiple_candidates(
                    optimizer.model_parameters
                ),
                response_model=ToolDescriptionCandidatesResponse,
            )

            responses = response if isinstance(response, list) else [response]

            allowed_tools = set(tool_names) if tool_names else None
            candidates: list[chat_prompt.ChatPrompt] = []
            for response_item in responses:
                for candidate in response_item.prompts:
                    updates: dict[str, str] = {}
                    for tool_name, description in candidate.tool_descriptions.items():
                        if allowed_tools is not None and tool_name not in allowed_tools:
                            continue
                        if not description or not isinstance(description, str):
                            continue
                        segment_id = (
                            f"{prompt_segments.PROMPT_SEGMENT_PREFIX_TOOL}{tool_name}"
                        )
                        updates[segment_id] = description.strip()
                    parameter_descriptions = candidate.parameter_descriptions or {}
                    for tool_name, params in parameter_descriptions.items():
                        if allowed_tools is not None and tool_name not in allowed_tools:
                            continue
                        if not isinstance(params, dict):
                            continue
                        for param_name, description in params.items():
                            if not description or not isinstance(description, str):
                                continue
                            segment_id = prompt_segments.tool_param_segment_id(
                                tool_name, param_name
                            )
                            updates[segment_id] = description.strip()
                    if not updates:
                        logger.warning(
                            "Skipping tool description candidate with no updates"
                        )
                        continue
                    updated_prompt = prompt_segments.apply_segment_updates(
                        current_prompt, updates
                    )
                    candidates.append(updated_prompt)

            if not candidates:
                raise ValueError("No valid tool description candidates returned.")

            candidate_generation_report.set_generated_prompts()
            return candidates

        except Exception as exc:
            if isinstance(
                exc, (_llm_calls.StructuredOutputParsingError, BadRequestError)
            ):
                raise
            raise ValueError(
                f"Unexpected error during tool description generation: {exc}"
            )


def _resolve_tool_segments(
    current_prompt: chat_prompt.ChatPrompt,
    tool_names: list[str] | None,
) -> list[prompt_segments.PromptSegment]:
    segments = prompt_segments.extract_prompt_segments(current_prompt)
    tool_segments = [segment for segment in segments if segment.is_tool()]
    if not tool_segments:
        raise ValueError("Prompt has no tools to optimize")

    if tool_names:
        resolved: list[prompt_segments.PromptSegment] = []
        available = {segment.segment_id: segment for segment in tool_segments}
        for tool_name in tool_names:
            segment_id = f"{prompt_segments.PROMPT_SEGMENT_PREFIX_TOOL}{tool_name}"
            segment = available.get(segment_id)
            if segment is None:
                raise ValueError(
                    f"Tool '{tool_name}' not found in prompt tools. Available: {sorted(available)}"
                )
            resolved.append(segment)
        return resolved

    return tool_segments


def report_tool_descriptions(
    prompt: chat_prompt.ChatPrompt,
    tool_names: list[str] | None,
    reporter: ToolDescriptionReporter,
) -> None:
    """Report tool descriptions using a caller-provided reporter."""
    segments = prompt_segments.extract_prompt_segments(prompt)
    tool_segments = [segment for segment in segments if segment.is_tool()]
    if tool_names:
        allowed = {
            f"{prompt_segments.PROMPT_SEGMENT_PREFIX_TOOL}{name}" for name in tool_names
        }
        tool_segments = [
            segment for segment in tool_segments if segment.segment_id in allowed
        ]
    for segment in tool_segments:
        tool_name = segment.segment_id.replace(
            prompt_segments.PROMPT_SEGMENT_PREFIX_TOOL, "", 1
        )
        reporter(segment.content, tool_name, segment.metadata)


def make_tool_description_reporter(
    display_fn: Callable[[str, str], None],
) -> ToolDescriptionReporter:
    """Return a reporter that appends tool parameters before display."""

    def _reporter(
        description: str,
        name: str,
        metadata: dict[str, Any],
    ) -> None:
        signature = ""
        raw_tool = metadata.get("raw_tool") or {}
        parameters = (
            raw_tool.get("function", {}).get("parameters")
            if isinstance(raw_tool, dict)
            else None
        )
        if parameters:
            signature = "\n\nTool parameters:\n" + json.dumps(
                parameters,
                indent=2,
                sort_keys=True,
                default=str,
            )
        text = f"{description}{signature}"
        display_fn(text, name)

    return _reporter


def _build_tool_blocks(segments: list[prompt_segments.PromptSegment]) -> str:
    blocks: list[str] = []
    for segment in segments:
        tool_name = segment.segment_id.replace(
            prompt_segments.PROMPT_SEGMENT_PREFIX_TOOL, "", 1
        )
        tool_metadata = segment.metadata.get("raw_tool", {})
        tool_metadata_json = json.dumps(
            tool_metadata, indent=2, sort_keys=True, default=str
        )
        block = (
            f"Tool name: {tool_name}\n"
            f"Tool description:\n{segment.content}\n"
            f"Tool metadata (JSON):\n{tool_metadata_json}"
        )
        blocks.append(block)
    return "\n\n".join(blocks)
