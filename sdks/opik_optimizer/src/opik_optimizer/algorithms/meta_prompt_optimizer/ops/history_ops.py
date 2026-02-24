"""
History operations for the Meta-Prompt Optimizer.
"""

from typing import Any
from collections.abc import Sequence
import logging
from ....core import runtime
from ....core.results import OptimizationRound, round_payload
from ....core.state import OptimizationContext
from .... import constants
from ....utils.display import format as display_format
from . import halloffame_ops


def record_round_history(
    *,
    optimizer: Any,
    context: OptimizationContext,
    round_num: int,
    prompt_scores: list[tuple[Any, float]],
    best_cand_score_avg: float,
    best_candidate_this_round: Any,
    improvement: float,
) -> None:
    """Record trials and finalize the round history entry."""
    round_handle = optimizer.pre_round(context)
    optimizer.set_selection_meta(
        {
            "selection_policy": optimizer.selection_strategy,
            "score_used": best_cand_score_avg,
            "candidate_count": len(prompt_scores),
        }
    )
    metadata_by_bundle = getattr(optimizer, "_candidate_metadata_by_bundle_id", {})
    for idx, (cand_prompt, cand_score) in enumerate(prompt_scores, start=1):
        metadata = metadata_by_bundle.get(id(cand_prompt), {})
        improvement_focus = metadata.get("improvement_focus")
        reasoning = metadata.get("reasoning")
        extra: dict[str, Any] = {"round_num": round_num}
        if improvement_focus is not None:
            extra["improvement_focus"] = improvement_focus
        if reasoning is not None:
            extra["reasoning"] = reasoning
        runtime.record_and_post_trial(
            optimizer=optimizer,
            context=context,
            prompt_or_payload=cand_prompt,
            score=cand_score,
            candidate_id=f"round{round_num}_cand{idx}",
            metrics={"selection_score": cand_score},
            notes=improvement_focus,
            round_handle=round_handle,
            post_metrics=None,
            post_extras=extra,
        )
    optimizer.post_round(
        round_handle=round_handle,
        context=context,
        best_score=best_cand_score_avg,
        best_candidate=best_candidate_this_round,
        stop_reason=context.finish_reason if context.should_stop else None,
        extras={
            "improvement": improvement,
            "best_so_far": context.current_best_score,
        },
    )


logger = logging.getLogger(__name__)


def build_history_context(
    previous_rounds: Sequence[OptimizationRound],
    hall_of_fame: Any | None = None,
    pretty_mode: bool = True,
    top_prompts_per_round: int = constants.META_PROMPT_DEFAULT_TOP_PROMPTS_PER_RECENT_ROUND,
) -> str:
    """
    Build context from Hall of Fame (if available) or previous optimization rounds.
    """
    context = ""

    if hall_of_fame:
        hall_context = halloffame_ops.build_hall_of_fame_context(
            hall_of_fame=hall_of_fame,
            pretty_mode=pretty_mode,
            max_entries=5,
        )
        if hall_context:
            context += hall_context

    rounds_list = list(previous_rounds)

    if rounds_list:
        context += "\nRecent Rounds - What We Just Tried:\n"
        context += "=" * 80 + "\n"
        context += (
            "CRITICAL: These are the prompts we JUST generated in recent rounds.\n"
        )
        context += "- If scores are LOWER than Hall of Fame: identify what's missing and avoid repeating these patterns\n"
        context += "- If scores are declining: we're moving in the wrong direction, course correct\n"
        context += "- Compare against winners: what did recent attempts lack? What mistakes were made?\n"
        context += (
            "- DO NOT generate similar variations of recent low-scoring prompts\n\n"
        )
        for round_data in reversed(rounds_list[-3:]):
            payload = round_payload(round_data)
            round_index = payload.get("round_index", 0)
            best_score = payload.get("best_score", float("nan"))
            context += f"\nRound {round_index + 1}:\n"
            context += f"Best score this round: {best_score:.4f}\n"
            context += "Top prompts generated:\n"

            generated = (
                payload.get("generated_prompts") or payload.get("candidates") or []
            )
            sorted_generated = sorted(
                generated,
                key=lambda p: p.get("score", -float("inf")),
                reverse=True,
            )

            for p in sorted_generated[:top_prompts_per_round]:
                prompt_data = p.get("candidate") or p.get("prompt", "N/A")
                score = p.get("score", float("nan"))
                context += f"- Score {score:.4f}:\n"

                if isinstance(prompt_data, (list, dict)):
                    context += display_format.format_prompt_payload(
                        prompt_data, pretty=pretty_mode
                    )
                    context += "\n\n"
                else:
                    context += f"{prompt_data}\n\n"

    return context
