"""Candidate assembly and selection helpers for GEPA."""

from __future__ import annotations

from typing import Any

from ....utils.candidate import unique_ordered_by_key
from ....api_objects.types import rebuild_content_with_new_text


# TODO: Promote to a shared optimizer candidate helper once GEPA selection is generalized.

def build_seed_candidate(
    *,
    optimizable_prompts: dict[str, Any],
) -> dict[str, str]:
    seed_candidate: dict[str, str] = {}
    for prompt_name, prompt_obj in optimizable_prompts.items():
        messages = prompt_obj.get_messages()
        for idx, msg in enumerate(messages):
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

            if optimized_text is not None:
                new_content = rebuild_content_with_new_text(
                    original_content, optimized_text
                )
            else:
                new_content = original_content

            new_messages.append({"role": msg["role"], "content": new_content})

        new_prompt = prompt_obj.copy()
        new_prompt.set_messages(new_messages)
        rebuilt[prompt_name] = new_prompt
    return rebuilt
