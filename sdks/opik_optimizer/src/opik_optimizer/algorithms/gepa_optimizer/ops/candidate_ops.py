"""Candidate assembly and selection helpers for GEPA."""

from __future__ import annotations

from typing import Any

from ....utils.candidate import unique_ordered_by_key
from ....api_objects.types import rebuild_content_with_new_text
from ....utils.toolcalling.core import segment_updates

TOOL_COMPONENT_PREFIX = segment_updates.TOOL_COMPONENT_PREFIX
TOOL_PARAM_COMPONENT_PREFIX = segment_updates.TOOL_PARAM_COMPONENT_PREFIX


# TODO: Promote to a shared optimizer candidate helper once GEPA selection is generalized.


def build_seed_candidate(
    *,
    optimizable_prompts: dict[str, Any],
    allowed_roles: set[str] | None = None,
    tool_names: list[str] | None = None,
    enable_tools: bool = False,
) -> dict[str, str]:
    seed_candidate: dict[str, str] = {}
    for prompt_name, prompt_obj in optimizable_prompts.items():
        messages = prompt_obj.get_messages()
        for idx, msg in enumerate(messages):
            role = msg.get("role")
            if allowed_roles is not None and role not in allowed_roles:
                continue
            component_key = f"{prompt_name}_{msg['role']}_{idx}"
            content = msg.get("content", "")
            if isinstance(content, list):
                text_parts = [
                    part.get("text", "")
                    for part in content
                    if isinstance(part, dict) and part.get("type") == "text"
                ]
                content = " ".join(text_parts)
            seed_candidate[component_key] = str(content)
        if enable_tools:
            # Include tool description components when tool optimization is enabled.
            seed_candidate.update(
                segment_updates.build_tool_component_seed(
                    prompt_name=prompt_name,
                    prompt=prompt_obj,
                    tool_names=tool_names,
                )
            )
    return seed_candidate


def filter_duplicate_candidates(
    *,
    candidates: list[dict[str, str]],
    val_scores: list[float],
) -> tuple[list[dict[str, str]], list[float | None], list[tuple[int, dict[str, str]]]]:
    indexed_candidates: list[tuple[int, dict[str, str]]] = list(enumerate(candidates))
    filtered_indexed_candidates = unique_ordered_by_key(
        indexed_candidates,
        key=lambda item: str(sorted(item[1].items())),
    )
    filtered_candidates: list[dict[str, str]] = [
        candidate for _, candidate in filtered_indexed_candidates
    ]
    filtered_val_scores: list[float | None] = [
        val_scores[idx] if idx < len(val_scores) else None
        for idx, _ in filtered_indexed_candidates
    ]
    return filtered_candidates, filtered_val_scores, filtered_indexed_candidates


def select_best_candidate_index(
    *,
    rescored: list[float],
    filtered_val_scores: list[float | None],
    filtered_indexed_candidates: list[tuple[int, dict[str, str]]],
    initial_score: float,
    gepa_result: Any,
) -> tuple[int, float]:
    if rescored:

        def _tie_break(idx: int) -> tuple[float, float, int]:
            opik_score = rescored[idx]
            gepa_score = filtered_val_scores[idx]
            gepa_numeric = (
                float(gepa_score)
                if isinstance(gepa_score, (int, float))
                else float("-inf")
            )
            return opik_score, gepa_numeric, idx

        best_idx = max(range(len(rescored)), key=_tie_break)
        best_score = rescored[best_idx]
        if best_score <= initial_score:
            return -1, float(initial_score)
        return best_idx, best_score

    if filtered_indexed_candidates:
        gepa_best_idx = getattr(gepa_result, "best_idx", 0) or 0
        best_idx = next(
            (
                i
                for i, (original_idx, _) in enumerate(filtered_indexed_candidates)
                if original_idx == gepa_best_idx
            ),
            0,
        )
        if filtered_val_scores and 0 <= best_idx < len(filtered_val_scores):
            score_value = filtered_val_scores[best_idx]
            best_score = float(score_value) if score_value is not None else 0.0
        else:
            best_score = float(initial_score)
        return best_idx, best_score

    return 0, float(initial_score)


def rebuild_prompts_from_candidate(
    *,
    base_prompts: dict[str, Any],
    candidate: dict[str, str],
    allowed_roles: set[str] | None = None,
) -> dict[str, Any]:
    """Rebuild prompts with optimized messages from a GEPA candidate."""
    rebuilt: dict[str, Any] = {}
    for prompt_name, prompt_obj in base_prompts.items():
        original_messages = prompt_obj.get_messages()
        new_messages = []
        for idx, msg in enumerate(original_messages):
            component_key = f"{prompt_name}_{msg['role']}_{idx}"
            original_content = msg.get("content", "")
            optimized_text = candidate.get(component_key)

            if optimized_text is not None and (
                allowed_roles is None or msg.get("role") in allowed_roles
            ):
                new_content = rebuild_content_with_new_text(
                    original_content, optimized_text
                )
            else:
                new_content = original_content

            new_messages.append({"role": msg["role"], "content": new_content})

        new_prompt = prompt_obj.copy()
        new_prompt.set_messages(new_messages)
        new_prompt = segment_updates.apply_tool_updates_from_candidate(
            candidate=candidate,
            prompt=new_prompt,
            tool_component_prefix=f"{prompt_name}{TOOL_COMPONENT_PREFIX}",
            tool_param_component_prefix=f"{prompt_name}{TOOL_PARAM_COMPONENT_PREFIX}",
        )
        rebuilt[prompt_name] = new_prompt
    return rebuilt


def count_disallowed_candidate_components(
    candidate: dict[str, str],
    allowed_roles: set[str] | None,
) -> int:
    """Count candidate components that target disallowed roles."""
    if allowed_roles is None:
        return 0
    if not allowed_roles:
        return sum(1 for key in candidate.keys() if key)
    count = 0
    for key in candidate.keys():
        if TOOL_COMPONENT_PREFIX in key or TOOL_PARAM_COMPONENT_PREFIX in key:
            continue
        parts = key.rsplit("_", 2)
        if len(parts) != 3:
            continue
        role = parts[1]
        if role not in allowed_roles:
            count += 1
    return count
