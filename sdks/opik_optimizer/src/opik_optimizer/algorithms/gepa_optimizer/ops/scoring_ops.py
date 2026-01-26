"""Candidate rescoring helpers for GEPA."""

from __future__ import annotations

from typing import Any
import logging

from ....utils.reporting import convert_tqdm_to_rich, suppress_opik_logs
from . import candidate_ops, history_ops

logger = logging.getLogger(__name__)


# TODO: Merge with other optimizer scoring helpers once GEPA selection is generalized.


def rescore_candidates(
    *,
    optimizer: Any,
    context: Any,
    dataset: Any,
    optimizable_prompts: dict[str, Any],
    filtered_indexed_candidates: list[tuple[int, dict[str, str]]],
    filtered_val_scores: list[float | None],
    selection_policy: str,
) -> list[float]:
    rescored: list[float] = []
    optimizer._history_builder.clear()
    default_split = (
        "validation" if optimizer._validation_dataset is not None else "train"
    )
    optimizer.set_default_dataset_split(default_split)

    with suppress_opik_logs():
        with convert_tqdm_to_rich(verbose=0):
            round_handle = optimizer.pre_round(context, candidate_id="gepa_candidates")
            best_score = None
            dataset_name = getattr(dataset, "name", None)
            for idx, (_, candidate) in enumerate(filtered_indexed_candidates):
                candidate_id = candidate.get("id") or f"gepa_candidate_{idx}"
                allowed_roles = (
                    context.extra_params.get("optimizable_roles")
                    if context.extra_params
                    else None
                )
                dropped_components = candidate_ops.count_disallowed_candidate_components(
                    candidate, allowed_roles
                )
                prompt_variants = candidate_ops.rebuild_prompts_from_candidate(
                    base_prompts=optimizable_prompts,
                    candidate=candidate,
                    allowed_roles=allowed_roles,
                )

                try:
                    context.evaluation_dataset = dataset
                    sampling_tag = optimizer._build_sampling_tag(
                        scope="gepa",
                        candidate_id=candidate_id,
                    )
                    score = optimizer.evaluate(
                        context,
                        prompt_variants,
                        sampling_tag=sampling_tag,
                    )
                    score = float(score)
                except Exception:
                    logger.debug(
                        "Rescoring failed for candidate %s", idx, exc_info=True
                    )
                    score = 0.0

                rescored.append(score)
                optimizer._gepa_rescored_scores = rescored
                optimizer._gepa_filtered_val_scores = filtered_val_scores
                best_score = score if best_score is None else max(best_score, score)

                history_ops.record_candidate_round(
                    optimizer=optimizer,
                    context=context,
                    round_handle=round_handle,
                    candidate_index=idx,
                    candidate_id=candidate_id,
                    candidate=candidate,
                    prompt_variants=prompt_variants,
                    score=score,
                    filtered_val_score=(
                        filtered_val_scores[idx]
                        if idx < len(filtered_val_scores)
                        else None
                    ),
                    selection_policy=selection_policy,
                    dataset_name=dataset_name,
                    dropped_components=dropped_components,
                )
            optimizer.set_selection_meta(
                {
                    "selection_policy": selection_policy,
                    "opik_rescored_scores": optimizer._gepa_rescored_scores,
                    "gepa_scores": optimizer._gepa_filtered_val_scores,
                }
            )
            optimizer.post_round(
                round_handle,
                context=context,
                best_score=best_score,
                extras={
                    "score_label": "opik_rescore",
                    "candidate_count": len(rescored),
                },
                stop_reason=context.finish_reason if context.should_stop else None,
            )

    return rescored
