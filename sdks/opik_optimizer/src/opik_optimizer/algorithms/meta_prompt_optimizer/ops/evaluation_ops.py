"""
Evaluation and selection helpers for the Meta-Prompt Optimizer.
"""

from typing import Any
from collections.abc import Sequence

from ....api_objects import chat_prompt
from ....utils import display as display_utils
from .. import reporting
from . import result_ops


def select_best_candidate(
    *,
    prompt_scores: Sequence[tuple[dict[str, chat_prompt.ChatPrompt], float]],
    best_score: float,
) -> tuple[
    list[tuple[dict[str, chat_prompt.ChatPrompt], float]],
    dict[str, chat_prompt.ChatPrompt],
    float,
    float,
]:
    """
    Sort prompt scores and return the top candidate with improvement.
    """
    sorted_scores = sorted(prompt_scores, key=lambda x: x[1], reverse=True)
    best_candidate, best_cand_score_avg = sorted_scores[0]
    improvement = result_ops.calculate_improvement(best_cand_score_avg, best_score)
    return sorted_scores, best_candidate, best_cand_score_avg, improvement


def score_candidate_prompts(
    *,
    optimizer: Any,
    context: Any,
    candidate_prompts: list[dict[str, chat_prompt.ChatPrompt]],
    best_score: float,
) -> tuple[list[tuple[Any, float]], float]:
    """
    Score candidate prompt bundles and return scores plus best score in round.
    """
    prompt_scores: list[tuple[Any, float]] = []
    current_round_best_score = best_score
    optimizer._total_candidates_in_round = len(candidate_prompts)
    dataset_name = getattr(getattr(context, "evaluation_dataset", None), "name", None)

    for candidate_count, candidate in enumerate(candidate_prompts):
        if optimizer._should_stop_context(context):
            break

        optimizer._current_candidate = candidate_count
        selection_summary = display_utils.summarize_selection_policy(candidate)
        with reporting.display_prompt_candidate_scoring_report(
            verbose=optimizer.verbose,
            dataset_name=dataset_name,
            is_validation=False,
            selection_summary=selection_summary,
        ) as reporter:
            reporter.set_generated_prompts(candidate_count, candidate)
            context.extra_params["suppress_evaluation_progress"] = True
            try:
                prompt_score = optimizer.evaluate(context, candidate)
            finally:
                context.extra_params.pop("suppress_evaluation_progress", None)
            reporter.set_final_score(current_round_best_score, prompt_score)

        if prompt_score > current_round_best_score:
            current_round_best_score = prompt_score

        prompt_scores.append((candidate, prompt_score))

    return prompt_scores, current_round_best_score
