"""
Candidate selection orchestration for Meta-Prompt Optimizer.
"""

import logging
from typing import Any
from collections.abc import Callable, Sequence

from litellm.exceptions import BadRequestError

from ....api_objects import chat_prompt
from ....api_objects.types import MetricFunction
from ....core.llm_calls import StructuredOutputParsingError
from ....core.results import OptimizationRound
from .candidate_bundle_ops import (  # noqa: F401
    _format_agent_prompts_for_prompt,
    generate_agent_bundle_candidates,
)
from .candidate_single_ops import (  # noqa: F401
    generate_candidate_prompts,
    sanitize_generated_prompts,
)
from .candidate_synthesis_ops import generate_synthesis_prompts
from ..types import AgentBundleCandidate, AgentMetadata  # noqa: F401

logger = logging.getLogger(__name__)

__all__ = [
    "AgentBundleCandidate",
    "AgentMetadata",
    "_format_agent_prompts_for_prompt",
    "generate_round_candidates",
    "sanitize_generated_prompts",
]


def _wrap_single_candidates(
    best_prompts: dict[str, chat_prompt.ChatPrompt],
    single_candidates: list[chat_prompt.ChatPrompt],
    prompts_this_round: int,
) -> tuple[list[dict[str, chat_prompt.ChatPrompt]], str]:
    prompt_key = next(iter(best_prompts.keys()))
    candidate_prompts = [
        {prompt_key: prompt} for prompt in single_candidates[:prompts_this_round]
    ]
    return candidate_prompts, prompt_key


def _attach_single_candidate_metadata(
    optimizer: Any,
    candidate_prompts: list[dict[str, chat_prompt.ChatPrompt]],
    prompt_key: str,
) -> None:
    for candidate in candidate_prompts:
        prompt = candidate.get(prompt_key)
        metadata = (
            optimizer._candidate_metadata_by_prompt_id.get(id(prompt))
            if prompt is not None
            else None
        )
        if metadata:
            optimizer._candidate_metadata_by_bundle_id[id(candidate)] = metadata


def _attach_bundle_candidate_metadata(
    optimizer: Any,
    bundle_candidates: list[AgentBundleCandidate],
    prompts_this_round: int,
) -> None:
    for bundle in bundle_candidates[:prompts_this_round]:
        focus_parts = []
        reasoning_parts = []
        for agent_name, meta in bundle.metadata.items():
            if meta.improvement_focus:
                focus_parts.append(f"{agent_name}: {meta.improvement_focus}")
            if meta.reasoning:
                reasoning_parts.append(f"{agent_name}: {meta.reasoning}")
        metadata = {
            "improvement_focus": "; ".join(focus_parts) or None,
            "reasoning": "; ".join(reasoning_parts) or None,
        }
        optimizer._candidate_metadata_by_bundle_id[id(bundle.prompts)] = metadata


def _generate_synthesis_candidates(
    *,
    optimizer: Any,
    best_prompts: dict[str, chat_prompt.ChatPrompt],
    best_score: float,
    previous_rounds: Sequence[OptimizationRound],
    metric: MetricFunction,
    get_task_context_fn: Callable,
    prompts_this_round: int,
    round_num: int,
    optimization_id: str | None,
    project_name: str | None,
) -> list[dict[str, chat_prompt.ChatPrompt]]:
    synthesis_candidates: list[dict[str, chat_prompt.ChatPrompt]] = []
    if (
        optimizer.synthesis_prompts_per_round > 0
        and round_num >= optimizer.synthesis_start_round
        and optimizer.synthesis_round_interval > 0
        and (round_num - optimizer.synthesis_start_round)
        % optimizer.synthesis_round_interval
        == 0
    ):
        synthesis_prompts = generate_synthesis_prompts(
            optimizer=optimizer,
            current_prompt=list(best_prompts.values())[0],
            best_score=best_score,
            previous_rounds=previous_rounds,
            metric=metric,
            get_task_context_fn=get_task_context_fn,
            optimization_id=optimization_id,
            project_name=project_name,
            round_num=round_num,
        )
        prompt_key = next(iter(best_prompts.keys()))
        synthesis_candidates = [{prompt_key: prompt} for prompt in synthesis_prompts]
        synthesis_candidates = synthesis_candidates[:prompts_this_round]
        _attach_single_candidate_metadata(optimizer, synthesis_candidates, prompt_key)
    return synthesis_candidates


def generate_round_candidates(
    *,
    optimizer: Any,
    best_prompts: dict[str, chat_prompt.ChatPrompt],
    best_score: float,
    round_num: int,
    previous_rounds: Sequence[OptimizationRound],
    metric: MetricFunction,
    prompts_this_round: int,
    build_history_context_fn: Callable,
    get_task_context_fn: Callable,
    optimization_id: str | None = None,
    project_name: str | None = None,
    is_single_prompt_optimization: bool,
    winning_patterns: list[str] | None = None,
) -> list[dict[str, chat_prompt.ChatPrompt]]:
    """
    Generate candidate prompt bundles for a single optimization round.

    Returns a list of prompt bundles (dict[str, ChatPrompt]) for evaluation.
    """
    try:
        optimizer._candidate_metadata_by_prompt_id = {}
        optimizer._candidate_metadata_by_bundle_id = {}
        if is_single_prompt_optimization:
            single_candidates = generate_candidate_prompts(
                optimizer=optimizer,
                current_prompt=list(best_prompts.values())[0],
                best_score=best_score,
                round_num=round_num,
                previous_rounds=previous_rounds,
                metric=metric,
                optimization_id=optimization_id,
                project_name=project_name,
                build_history_context_fn=build_history_context_fn,
                get_task_context_fn=get_task_context_fn,
                winning_patterns=winning_patterns,
                expected_count=prompts_this_round,
            )
            candidate_prompts, prompt_key = _wrap_single_candidates(
                best_prompts, single_candidates, prompts_this_round
            )
            _attach_single_candidate_metadata(optimizer, candidate_prompts, prompt_key)
        else:
            bundle_candidates = generate_agent_bundle_candidates(
                optimizer=optimizer,
                current_prompts=best_prompts,
                best_score=best_score,
                round_num=round_num,
                previous_rounds=previous_rounds,
                metric=metric,
                optimization_id=optimization_id,
                project_name=project_name,
                build_history_context_fn=build_history_context_fn,
                get_task_context_fn=get_task_context_fn,
                expected_count=prompts_this_round,
            )
            candidate_prompts = [
                bundle.prompts for bundle in bundle_candidates[:prompts_this_round]
            ]
            _attach_bundle_candidate_metadata(
                optimizer, bundle_candidates, prompts_this_round
            )

        if is_single_prompt_optimization:
            synthesis_candidates = _generate_synthesis_candidates(
                optimizer=optimizer,
                best_prompts=best_prompts,
                best_score=best_score,
                previous_rounds=previous_rounds,
                metric=metric,
                get_task_context_fn=get_task_context_fn,
                prompts_this_round=prompts_this_round,
                round_num=round_num,
                optimization_id=optimization_id,
                project_name=project_name,
            )
            if synthesis_candidates:
                candidate_prompts = synthesis_candidates + candidate_prompts
                candidate_prompts = candidate_prompts[:prompts_this_round]

        return candidate_prompts
    except Exception as exc:
        if isinstance(exc, (BadRequestError, StructuredOutputParsingError)):
            raise
        logger.warning("Failed to generate %s candidates: %s", prompts_this_round, exc)
        return []
