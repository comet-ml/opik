"""Result assembly for GEPA runs."""

from __future__ import annotations

from typing import Any

from ....core.state import AlgorithmResult
from . import candidate_ops
from ....utils.logging import debug_log


# TODO: Extract selection metadata stitching into a shared optimizer helper.


def build_algorithm_result(
    *,
    optimizer: Any,
    best_idx: int,
    best_score: float,
    best_candidate: dict[str, str],
    filtered_candidates: list[dict[str, str]],
    filtered_val_scores: list[float | None],
    rescored: list[float],
    candidate_selection_strategy: str,
    best_matches_seed: bool,
    seed_candidate: dict[str, str],
    optimizable_prompts: dict[str, Any],
    train_items: list[dict[str, Any]],
    gepa_result: Any,
    experiment_config: dict[str, Any] | None,
) -> AlgorithmResult:
    history_entries = optimizer.get_history_entries()
    if history_entries:
        chosen_idx = min(best_idx, len(history_entries) - 1)
        chosen = history_entries[chosen_idx]
        extra = chosen.get("extra") or {}
        extra.update(
            {
                "selected": True,
                "selection_metric": "opik_score",
                "selection_meta": {
                    "opik_score": rescored[best_idx] if rescored else None,
                    "gepa_score": (
                        filtered_val_scores[best_idx]
                        if filtered_val_scores
                        and 0 <= best_idx < len(filtered_val_scores)
                        else None
                    ),
                    "selection_policy": candidate_selection_strategy,
                },
            }
        )
        extra["acquisition"] = {
            "opik_score": rescored[best_idx] if rescored else None,
            "gepa_score": (
                filtered_val_scores[best_idx]
                if filtered_val_scores and 0 <= best_idx < len(filtered_val_scores)
                else None
            ),
        }
        chosen["extra"] = extra

    debug_log(
        "selection",
        candidate_index=best_idx,
        candidate_id=filtered_candidates[best_idx].get("id")
        if filtered_candidates and 0 <= best_idx < len(filtered_candidates)
        else None,
        best_score=best_score,
        selection_policy=candidate_selection_strategy,
    )

    final_prompts = candidate_ops.rebuild_prompts_from_candidate(
        base_prompts=optimizable_prompts,
        candidate=best_candidate,
    )

    metadata: dict[str, Any] = {
        "num_candidates": len(filtered_candidates),
        "num_components": len(seed_candidate),
        "total_metric_calls": getattr(gepa_result, "total_metric_calls", None),
        "parents": getattr(gepa_result, "parents", None),
        "val_scores": filtered_val_scores,
        "opik_rescored_scores": rescored,
        "candidate_summary": [],
        "best_candidate_iteration": 0,
        "selected_candidate_index": best_idx if filtered_candidates else None,
        "selected_candidate_gepa_score": (
            filtered_val_scores[best_idx]
            if filtered_val_scores and 0 <= best_idx < len(filtered_val_scores)
            else None
        ),
        "selected_candidate_opik_score": best_score,
        "adapter_metric_used": True,
        "adapter_metric_call_count": optimizer._adapter_metric_calls,
        "dataset_item_ids": [item.get("id") for item in train_items],
    }
    if best_matches_seed:
        metadata["final_evaluation_reused_baseline"] = True
    if experiment_config:
        metadata["experiment"] = experiment_config

    return AlgorithmResult(
        best_prompts=final_prompts,
        best_score=best_score,
        history=optimizer.get_history_entries(),
        metadata=metadata,
    )
