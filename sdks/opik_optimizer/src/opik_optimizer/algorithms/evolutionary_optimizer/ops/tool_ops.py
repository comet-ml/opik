from __future__ import annotations

import copy
import logging
from typing import Any

from ....api_objects import chat_prompt
from ....utils.toolcalling.ops import toolcalling as toolcalling_utils

logger = logging.getLogger(__name__)


def apply_tool_updates_to_metadata(
    *,
    optimizer: Any,
    child_data: dict[str, list[dict[str, Any]]],
    metadata: dict[str, Any],
    tool_names: list[str] | None,
    metric: Any,
) -> dict[str, Any]:
    """Apply tool description updates to prompts metadata."""
    updated_metadata = copy.deepcopy(metadata)
    for prompt_name, messages in child_data.items():
        prompt_meta = updated_metadata.get(prompt_name, {})
        tools = prompt_meta.get("tools")
        if not tools:
            continue
        # Initial chatprompt
        prompt = chat_prompt.ChatPrompt(
            messages=messages,
            tools=tools,
            function_map=prompt_meta.get("function_map"),
            name=prompt_meta.get("name", prompt_name),
            model=prompt_meta.get("model"),
            model_parameters=prompt_meta.get("model_kwargs"),
        )
        # Apply tool signature changes
        prompt = apply_tool_description_update(
            optimizer=optimizer,
            prompt=prompt,
            tool_names=tool_names,
            round_num=0,
            metric=metric,
        )
        # Finalize prompt and metadata
        prompt_meta = dict(prompt_meta)
        prompt_meta["tools"] = prompt.tools
        prompt_meta["function_map"] = prompt.function_map
        prompt_meta["model"] = prompt.model
        prompt_meta["model_kwargs"] = copy.deepcopy(prompt.model_kwargs)
        updated_metadata[prompt_name] = prompt_meta
    return updated_metadata


def apply_tool_description_update(
    *,
    optimizer: Any,
    prompt: chat_prompt.ChatPrompt,
    tool_names: list[str] | None,
    round_num: int,
    metric: Any,
) -> chat_prompt.ChatPrompt:
    """Return a prompt updated with tool description candidates.

    Uses a single-candidate pass (`prompts_per_round=1`) because evolutionary
    mutation/crossover/population ops expect a single prompt per individual.
    Generating multiple tool candidates inside these steps would require an
    additional selection/evaluation loop, so we force a deterministic, single
    update to keep the evolutionary pipeline stable.
    """
    if not prompt.tools:
        return prompt
    if metric is None:
        return prompt
    # Force single candidate round
    original_prompts_per_round = getattr(optimizer, "prompts_per_round", None)
    optimizer.prompts_per_round = 1
    # Build candidate prompt with tools
    try:
        try:
            candidates = toolcalling_utils.generate_tool_description_candidates(
                optimizer=optimizer,
                current_prompt=prompt,
                best_score=0.0,
                round_num=round_num,
                previous_rounds=optimizer.get_history_rounds(),
                metric=metric,
                build_history_context_fn=lambda _rounds: "",
                tool_names=tool_names,
                optimization_id=optimizer.current_optimization_id,
                project_name=optimizer.project_name,
                tool_description_reporter=None,
            )
            return candidates[0] if candidates else prompt
        except Exception as exc:
            logger.warning(
                "Tool description update failed during evolutionary step; keeping current prompt. error=%s",
                exc,
            )
            logger.debug("Tool description update traceback", exc_info=True)
            return prompt
    finally:
        if original_prompts_per_round is None:
            delattr(optimizer, "prompts_per_round")
        else:
            optimizer.prompts_per_round = original_prompts_per_round
