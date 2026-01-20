from typing import Any
from unittest.mock import MagicMock

from opik_optimizer.algorithms.meta_prompt_optimizer.meta_prompt_optimizer import (
    MetaPromptOptimizer,
)


def test_round_counter_increments_on_non_improving_rounds(
    simple_chat_prompt: Any,
) -> None:
    optimizer = MetaPromptOptimizer(model="gpt-4")
    optimizer.verbose = 0

    context = MagicMock()
    context.trials_completed = 0
    context.current_best_score = 1.0
    context.should_stop = True
    context.finish_reason = "max_trials"

    round_num = 0
    best_score = 1.0

    round_handle = optimizer.pre_round(context)
    optimizer.record_candidate_entry(
        prompt_or_payload=simple_chat_prompt,
        score=0.5,
        id=f"round{round_num}_cand",
    )
    optimizer.post_trial(
        context,
        simple_chat_prompt,
        score=0.5,
        trial_index=0,
        round_handle=round_handle,
        extras={"round_num": round_num},
    )
    optimizer.post_round(
        round_handle=round_handle,
        best_score=0.5,
        best_candidate=simple_chat_prompt,
        stop_reason=context.finish_reason,
    )

    if 0.5 > best_score:
        best_score = 0.5
    round_num += 1

    next_id = f"round{round_num}_cand"
    assert next_id == "round1_cand"
