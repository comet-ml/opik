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
            for idx, (_, candidate) in enumerate(filtered_indexed_candidates):
                prompt_variants = candidate_ops.rebuild_prompts_from_candidate(
                    base_prompts=optimizable_prompts,
                    candidate=candidate,
                )

                try:
                    context.evaluation_dataset = dataset
                    score = optimizer.evaluate(context, prompt_variants)
                    score = float(score)
                except Exception:
                    logger.debug(
                        "Rescoring failed for candidate %s", idx, exc_info=True
                    )
                    score = 0.0

                rescored.append(score)
                optimizer._gepa_rescored_scores = rescored
                optimizer._gepa_filtered_val_scores = filtered_val_scores

                candidate_id = candidate.get("id") or f"gepa_candidate_{idx}"
                history_ops.record_candidate_round(
                    optimizer=optimizer,
                    context=context,
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
                )

    return rescored
