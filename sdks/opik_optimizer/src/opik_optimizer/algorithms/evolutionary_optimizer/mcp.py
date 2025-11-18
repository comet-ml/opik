"""MCP helper utilities for the Evolutionary Optimizer."""

from __future__ import annotations

import json
import logging
import re
import textwrap
from dataclasses import dataclass
from typing import Any

from ...api_objects import chat_prompt
from ... import _llm_calls
from ...utils.prompt_segments import (
    apply_segment_updates,
    extract_prompt_segments,
)

from . import prompts as evo_prompts
from . import reporting

logger = logging.getLogger(__name__)


@dataclass
class EvolutionaryMCPContext:
    tool_name: str
    tool_segment_id: str
    original_description: str
    tool_metadata: dict[str, Any]
    panel_style: str


def _tool_metadata_json(metadata: dict[str, Any]) -> str:
    try:
        return json.dumps(metadata, indent=2)
    except (
        TypeError,
        ValueError,
    ):  # pragma: no cover - defensive, shouldn't happen under normal circumstances
        return str(metadata)


def generate_tool_description_variations(
    base_prompt: chat_prompt.ChatPrompt,
    context: EvolutionaryMCPContext,
    num_variations: int,
    model: str,
    model_parameters: dict[str, Any],
    optimization_id: str | None,
) -> list[chat_prompt.ChatPrompt]:
    if num_variations <= 0:
        return []

    instruction = textwrap.dedent(
        evo_prompts.mcp_tool_rewrite_user_prompt(
            tool_name=context.tool_name,
            current_description=_current_tool_description(
                base_prompt, context.tool_segment_id
            )
            or context.original_description,
            tool_metadata_json=_tool_metadata_json(context.tool_metadata),
            num_variations=num_variations,
        )
    ).strip()

    try:
        response = _llm_calls.call_model(
            messages=[
                {
                    "role": "system",
                    "content": evo_prompts.mcp_tool_rewrite_system_prompt(),
                },
                {"role": "user", "content": instruction},
            ],
            is_reasoning=True,
            model=model,
            model_parameters=model_parameters,
            optimization_id=optimization_id,
        )

        payload = _extract_json_payload(response)
        prompts_payload = payload.get("prompts")
        if not isinstance(prompts_payload, list):
            raise ValueError("LLM response missing 'prompts' list")

        candidates: list[chat_prompt.ChatPrompt] = []
        seen: set[str] = set()
        for item in prompts_payload:
            if not isinstance(item, dict):
                continue
            description = item.get("tool_description")
            if not isinstance(description, str) or not description.strip():
                continue
            normalized = description.strip()
            if normalized in seen:
                continue
            seen.add(normalized)
            updated_prompt = _apply_description(base_prompt, context, normalized)
            reporting.display_tool_description(
                normalized,
                f"Candidate tool description ({context.tool_name})",
                context.panel_style,
            )
            candidates.append(updated_prompt)
            if len(candidates) >= num_variations:
                break

        return candidates
    except Exception as exc:  # pragma: no cover - fallback path
        logger.warning(f"Failed to generate MCP tool descriptions: {exc}")
        return []


def initialize_population_mcp(
    prompt: chat_prompt.ChatPrompt,
    context: EvolutionaryMCPContext,
    model: str,
    model_parameters: dict[str, Any],
    optimization_id: str | None,
    population_size: int,
    verbose: int,
) -> list[chat_prompt.ChatPrompt]:
    with reporting.initializing_population(verbose=verbose) as init_pop_report:
        init_pop_report.start(population_size)

        population = [prompt]
        num_to_generate = max(0, population_size - 1)
        if num_to_generate > 0:
            candidates = generate_tool_description_variations(
                base_prompt=prompt,
                context=context,
                num_variations=num_to_generate,
                model=model,
                model_parameters=model_parameters,
                optimization_id=optimization_id,
            )
            population.extend(candidates[:num_to_generate])

        seen: set[str] = set()
        final_population: list[chat_prompt.ChatPrompt] = []
        for candidate in population:
            key = json.dumps(candidate.get_messages())
            if key in seen:
                continue
            seen.add(key)
            final_population.append(candidate)

        while len(final_population) < population_size:
            final_population.append(prompt)

        init_pop_report.end(final_population)
        return final_population[:population_size]


def tool_description_mutation(
    prompt: chat_prompt.ChatPrompt,
    context: EvolutionaryMCPContext,
    model: str,
    model_parameters: dict[str, Any],
    optimization_id: str | None,
) -> chat_prompt.ChatPrompt | None:
    candidates = generate_tool_description_variations(
        base_prompt=prompt,
        context=context,
        num_variations=1,
        model=model,
        model_parameters=model_parameters,
        optimization_id=optimization_id,
    )
    if not candidates:
        return None

    description = _current_tool_description(candidates[0], context.tool_segment_id)
    if description:
        reporting.display_tool_description(
            description,
            f"Updated tool description ({context.tool_name})",
            context.panel_style,
        )
    return candidates[0]


def finalize_mcp_result(
    result: Any,
    context: EvolutionaryMCPContext,
    panel_style: str,
    optimizer: Any = None,
) -> None:
    final_tools = (
        result.details.get("final_tools") if isinstance(result.details, dict) else None
    )
    # Use optimizer's centralized method if available, otherwise inline
    if optimizer and hasattr(optimizer, "_extract_tool_prompts"):
        tool_prompts = optimizer._extract_tool_prompts(final_tools) or {}
    else:
        tool_prompts = {
            (tool.get("function", {}).get("name") or tool.get("name")): tool.get(
                "function", {}
            ).get("description")
            for tool in (final_tools or [])
        }
    if tool_prompts.get(context.tool_name):
        reporting.display_tool_description(
            tool_prompts[context.tool_name],
            f"Final tool description ({context.tool_name})",
            panel_style,
        )

    if not tool_prompts and context.original_description:
        tool_prompts = {context.tool_name: context.original_description}

    if tool_prompts:
        result.tool_prompts = tool_prompts


# ---------------------------------------------------------------------------
# Internal helpers
# ---------------------------------------------------------------------------


def _current_tool_description(
    prompt: chat_prompt.ChatPrompt,
    tool_segment_id: str,
) -> str:
    segments = {
        segment.segment_id: segment for segment in extract_prompt_segments(prompt)
    }
    target = segments.get(tool_segment_id)
    return target.content if target else ""


def _extract_json_payload(response: str) -> dict[str, Any]:
    try:
        return json.loads(response)
    except json.JSONDecodeError:
        match = re.search(r"\{.*\}", response, re.DOTALL)
        if not match:
            raise ValueError("No JSON object found in LLM response")
        return json.loads(match.group())


def _apply_description(
    prompt: chat_prompt.ChatPrompt,
    context: EvolutionaryMCPContext,
    description: str,
) -> chat_prompt.ChatPrompt:
    updated_prompt = apply_segment_updates(
        prompt,
        {context.tool_segment_id: description},
    )
    _sync_system_description(updated_prompt, description)
    return updated_prompt


def _sync_system_description(prompt: chat_prompt.ChatPrompt, description: str) -> None:
    if not prompt.system:
        return

    marker_start = "<<TOOL_DESCRIPTION>>"
    marker_end = "<<END_TOOL_DESCRIPTION>>"

    start = prompt.system.find(marker_start)
    end = prompt.system.find(marker_end)
    if start == -1 or end == -1 or end <= start:
        return

    prefix = prompt.system[: start + len(marker_start)]
    suffix = prompt.system[end:]
    formatted_description = f"\n{description.strip()}\n"
    prompt.system = f"{prefix}{formatted_description}{suffix}"
