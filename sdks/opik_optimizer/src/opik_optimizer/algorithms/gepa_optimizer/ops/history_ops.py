"""History bookkeeping for GEPA rescoring and selection.

All candidate trial recording for GEPA lives here so other optimizers can reuse
this wiring later without duplicating logic in the main optimizer class.
"""

from __future__ import annotations

from typing import Any

from ....core import runtime
from ....utils.logging import debug_log


# TODO: Extract a shared candidate-recording helper once other optimizers converge on schema.


def record_candidate_round(
    *,
    optimizer: Any,
    context: Any,
    round_handle: Any | None = None,
    candidate_index: int,
    candidate_id: str,
    candidate: dict[str, str],
    prompt_variants: dict[str, Any],
    score: float,
    filtered_val_score: float | None,
    selection_policy: str,
) -> None:
    finalize_round = round_handle is None
    debug_log(
        "candidate_start",
        candidate_index=candidate_index,
        candidate_id=candidate_id,
        trials_completed=context.trials_completed,
    )
    components = {
        key: value
        for key, value in candidate.items()
        if not key.startswith("_") and key not in ("source", "id")
    }
    if round_handle is None:
        round_handle = optimizer.pre_round(context, candidate_id=candidate_id)
    runtime.record_and_post_trial(
        optimizer=optimizer,
        context=context,
        prompt_or_payload=prompt_variants,
        score=score,
        candidate_id=candidate_id,
        metrics={
            f"gepa_{context.metric.__name__}": filtered_val_score,
            context.metric.__name__: score,
        },
        extra={
            "components": components,
            "source": candidate.get("source"),
            "score_label": "opik_rescore",
        },
        round_handle=round_handle,
        post_extras={
            "components": components,
            "candidate_id": candidate_id,
            "score_label": "opik_rescore",
        },
    )
    debug_log(
        "candidate_end",
        candidate_index=candidate_index,
        candidate_id=candidate_id,
        score=score,
        trials_completed=context.trials_completed,
    )
    if finalize_round:
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
            extras={
                "score_label": "opik_rescore",
            },
            stop_reason=context.finish_reason if context.should_stop else None,
        )
