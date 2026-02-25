"""Shared helpers for tool description optimization across optimizers."""

from __future__ import annotations

import logging
import warnings
from typing import Any
from collections.abc import Callable

from pydantic import BaseModel, ConfigDict, Field
from ....api_objects import chat_prompt
from ....api_objects.types import MetricFunction
from ....utils import prompt_segments
from ....utils.display import format as display_format
from ....utils.display import display_text_block
from . import prompts as toolcalling_prompts
from ..normalize.tool_factory import ToolCallingFactory
from .rendering_ops import (
    ToolDescriptionReporter,
    build_tool_blocks_from_segments,
    build_tool_blocks_from_prompt as _build_tool_blocks_from_prompt,
    make_tool_description_reporter as _make_tool_description_reporter,
    report_tool_descriptions as _report_tool_descriptions,
)
from .selector_ops import prepare_tool_optimization as _prepare_tool_optimization

logger = logging.getLogger(__name__)


class CandidateGenerationReporter:
    """Reporter for candidate generation display output."""

    def __init__(self, num_prompts: int, selection_summary: str | None = None) -> None:
        """Create a reporter for a prompt cap and selection summary."""
        self.num_prompts = num_prompts
        self.selection_summary = selection_summary

    def set_generated_prompts(self, generated_count: int) -> None:
        """Display a summary line for generated candidate counts."""
        summary = f" ({self.selection_summary})" if self.selection_summary else ""
        cap_text = (
            f" (cap {self.num_prompts})" if generated_count > self.num_prompts else ""
        )
        display_text_block(
            f"│      Successfully generated {generated_count} candidate prompt{'' if generated_count == 1 else 's'}{cap_text}{summary}",
            style="dim",
        )
        display_text_block("│")


def display_candidate_generation_report(
    num_prompts: int, verbose: int = 1, selection_summary: str | None = None
) -> CandidateGenerationReporter:
    """Display the candidate generation header and return a reporter."""
    if verbose >= 1:
        display_text_block(
            f"│    Generating up to {num_prompts} candidate prompt{'' if num_prompts == 1 else 's'}:",
        )
        if selection_summary:
            display_text_block(
                f"│      Evaluation settings: {selection_summary}", style="dim"
            )
    return CandidateGenerationReporter(num_prompts, selection_summary)


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
        if optimize_tools is not None:
            raise ValueError(
                "Tool description optimization only supports single prompts."
            )
        if not any(
            getattr(prompt, "tools", None) for prompt in prompt_or_prompts.values()
        ):
            return prompt_or_prompts, None
        factory = ToolCallingFactory()
        resolved = {
            name: factory.resolve_prompt(prompt)
            for name, prompt in prompt_or_prompts.items()
        }
        return resolved, None

    if optimize_tools is not None:
        return _prepare_tool_optimization(prompt_or_prompts, optimize_tools)

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
    optimize_prompts: bool,
    optimize_tools: bool | dict[str, bool] | None,
    supports_tool_optimization: bool,
    warn_unsupported: bool = False,
) -> bool | dict[str, bool] | None:
    """Validate optimization flags and normalize tool optimization options."""
    if not optimize_prompts and not optimize_tools:
        raise ValueError("optimize_prompts and optimize_tools are both disabled.")

    if optimize_tools and not supports_tool_optimization:
        if not optimize_prompts:
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


class ToolDescriptionUpdate(BaseModel):
    """Single tool description update."""

    model_config = ConfigDict(extra="forbid")

    name: str = Field(..., description="Tool name")
    description: str = Field(..., description="Updated tool description")


class ToolParameterDescription(BaseModel):
    """Single parameter description update."""

    model_config = ConfigDict(extra="forbid")

    name: str = Field(..., description="Parameter name")
    description: str = Field(..., description="Updated parameter description")


class ToolParameterUpdate(BaseModel):
    """Parameter description updates for a tool."""

    model_config = ConfigDict(extra="forbid")

    tool_name: str = Field(..., description="Tool name")
    parameters: list[ToolParameterDescription] = Field(
        ..., description="Parameter descriptions"
    )


class ToolDescriptionCandidate(BaseModel):
    """Response model for a tool description candidate."""

    model_config = ConfigDict(extra="forbid")

    tool_descriptions: list[ToolDescriptionUpdate] = Field(
        ..., description="Updated tool descriptions"
    )
    parameter_descriptions: list[ToolParameterUpdate] | None = Field(
        None, description="Updated parameter descriptions"
    )
    improvement_focus: str = Field(
        ..., description="What aspect the description improves"
    )
    reasoning: str = Field(..., description="Explanation for the updated description")


class ToolDescriptionCandidatesResponse(BaseModel):
    """Response model for tool description candidate generation."""

    model_config = ConfigDict(extra="forbid")

    prompts: list[ToolDescriptionCandidate] = Field(
        ..., description="List of tool description candidates"
    )

    @classmethod
    def __get_pydantic_json_schema__(
        cls, _core_schema: Any, _handler: Any
    ) -> dict[str, Any]:
        """Return a strict JSON schema for OpenAI structured outputs."""
        return {
            "type": "object",
            "additionalProperties": False,
            "properties": {
                "prompts": {
                    "type": "array",
                    "items": {
                        "type": "object",
                        "additionalProperties": False,
                        "properties": {
                            "tool_descriptions": {
                                "type": "array",
                                "items": {
                                    "type": "object",
                                    "additionalProperties": False,
                                    "properties": {
                                        "name": {"type": "string"},
                                        "description": {"type": "string"},
                                    },
                                    "required": ["name", "description"],
                                },
                            },
                            "parameter_descriptions": {
                                "type": ["array", "null"],
                                "items": {
                                    "type": "object",
                                    "additionalProperties": False,
                                    "properties": {
                                        "tool_name": {"type": "string"},
                                        "parameters": {
                                            "type": "array",
                                            "items": {
                                                "type": "object",
                                                "additionalProperties": False,
                                                "properties": {
                                                    "name": {"type": "string"},
                                                    "description": {"type": "string"},
                                                },
                                                "required": ["name", "description"],
                                            },
                                        },
                                    },
                                    "required": ["tool_name", "parameters"],
                                },
                            },
                            "improvement_focus": {"type": "string"},
                            "reasoning": {"type": "string"},
                        },
                        "required": [
                            "tool_descriptions",
                            "improvement_focus",
                            "reasoning",
                        ],
                    },
                }
            },
            "required": ["prompts"],
        }


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
    from ....core import llm_calls as _llm_calls

    candidate_generation_report = display_candidate_generation_report(
        optimizer.prompts_per_round,
        verbose=optimizer.verbose,
        selection_summary=display_format.summarize_selection_policy(current_prompt),
    )

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
    tool_blocks_str = build_tool_blocks_from_segments(tool_segments)

    tool_description_user_template = optimizer.get_prompt("tool_description_user")
    tool_description_system_template = optimizer.get_prompt("tool_description_system")
    user_prompt = toolcalling_prompts.build_tool_description_user_prompt(
        tool_blocks=tool_blocks_str,
        best_score=best_score,
        history_context=history_context,
        prompts_per_round=optimizer.prompts_per_round,
        template=tool_description_user_template,
    )

    metadata_for_call = _llm_calls.build_llm_call_metadata(
        optimizer=optimizer,
        call_type="optimization_algorithm",
    )

    response = _llm_calls.call_model(
        messages=[
            {
                "role": "system",
                "content": toolcalling_prompts.build_tool_description_system_prompt(
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
        return_all=_llm_calls.requested_multiple_candidates(optimizer.model_parameters),
        response_model=ToolDescriptionCandidatesResponse,
    )

    responses = response if isinstance(response, list) else [response]

    allowed_tools = set(tool_names) if tool_names else None
    candidates: list[chat_prompt.ChatPrompt] = []
    for response_item in responses:
        for candidate in response_item.prompts:
            updates: dict[str, str] = {}
            for tool_update in candidate.tool_descriptions:
                tool_name = tool_update.name
                description = tool_update.description
                if allowed_tools is not None and tool_name not in allowed_tools:
                    continue
                if not description or not isinstance(description, str):
                    continue
                segment_id = f"{prompt_segments.PROMPT_SEGMENT_PREFIX_TOOL}{tool_name}"
                updates[segment_id] = description.strip()
            param_updates = candidate.parameter_descriptions or []
            for tool_update in param_updates:
                tool_name = tool_update.tool_name
                if allowed_tools is not None and tool_name not in allowed_tools:
                    continue
                for param in tool_update.parameters:
                    param_name = param.name
                    description = param.description
                    if not description or not isinstance(description, str):
                        continue
                    segment_id = prompt_segments.tool_param_segment_id(
                        tool_name, param_name
                    )
                    updates[segment_id] = description.strip()
            if not updates:
                logger.warning("Skipping tool description candidate with no updates")
                continue
            updated_prompt = prompt_segments.apply_segment_updates(
                current_prompt, updates
            )
            candidates.append(updated_prompt)

    if not candidates:
        raise ValueError("No valid tool description candidates returned.")

    candidate_generation_report.set_generated_prompts(len(candidates))
    return candidates


def _resolve_tool_segments(
    current_prompt: chat_prompt.ChatPrompt,
    tool_names: list[str] | None,
) -> list[prompt_segments.PromptSegment]:
    """Return tool segments filtered by ``tool_names``.

    Returns all tool segments when ``tool_names`` is ``None``.

    Raises:
        ValueError: ``"Prompt has no tools to optimize"`` when no tool segments exist.
        ValueError: ``"Tool '<name>' not found in prompt tools. Available: ..."``
            when a requested tool name is missing.
    """
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


# Backward-compatible exports from extracted ops modules.
prepare_tool_optimization = _prepare_tool_optimization
build_tool_blocks_from_prompt = _build_tool_blocks_from_prompt
report_tool_descriptions = _report_tool_descriptions
make_tool_description_reporter = _make_tool_description_reporter
