"""
Synthesis candidate generation for the Meta-Prompt Optimizer.
"""

from ....utils.prompt_editing import apply_text_edits

from ....core.reasoning import ReasoningEvidence

import ast
import json
import logging
from typing import Any
from collections.abc import Callable, Sequence

from litellm.exceptions import BadRequestError

from ....api_objects import chat_prompt
from ....api_objects.types import MetricFunction
from ....core import llm_calls as _llm_calls
from ....core.llm_calls import StructuredOutputParsingError
from ....core.results import OptimizationRound, round_payload
from ....utils import display as display_utils
from ....utils.text import normalize_llm_text
from ....utils.multimodal import text_only_messages
from .. import prompts as meta_prompts
from .. import reporting
from ..types import PromptCandidatesResponse

logger = logging.getLogger(__name__)


def _collect_hof_prompts(
    optimizer: Any,
) -> list[tuple[list[dict[str, str]], float, str]]:
    top_prompts_with_scores: list[tuple[list[dict[str, str]], float, str]] = []
    if not optimizer.hall_of_fame or not hasattr(optimizer.hall_of_fame, "entries"):
        return top_prompts_with_scores
    for entry in optimizer.hall_of_fame.entries[:5]:
        prompt_messages = entry.prompt_messages
        score = entry.score

        reasoning_parts = []
        reasoning_parts.append(f"Trial #{entry.trial_number}")
        reasoning_parts.append(
            f"Improvement: {entry.improvement_over_baseline * 100:+.1f}% over baseline"
        )

        if entry.extracted_patterns:
            reasoning_parts.append(
                f"Winning patterns: {' | '.join(entry.extracted_patterns)}"
            )

        if entry.metadata:
            for key, value in entry.metadata.items():
                if value and key not in ["prompt_messages", "score"]:
                    reasoning_parts.append(f"{key}: {value}")

        reasoning = " | ".join(reasoning_parts)
        top_prompts_with_scores.append((prompt_messages, score, reasoning))
    return top_prompts_with_scores


def _collect_recent_round_prompts(
    previous_rounds: Sequence[OptimizationRound],
) -> list[tuple[list[dict[str, str]], float, str]]:
    top_prompts_with_scores: list[tuple[list[dict[str, str]], float, str]] = []
    for round_data in reversed(list(previous_rounds)[-5:]):
        generated = round_payload(round_data).get("generated_prompts", [])
        sorted_generated = sorted(
            generated,
            key=lambda p: p.get("score", -float("inf")),
            reverse=True,
        )
        if not sorted_generated:
            continue
        best = sorted_generated[0]
        prompt_payload = best.get("candidate") or best.get("prompt", "")
        score = best.get("score", 0.0)
        reasoning = best.get("notes") or best.get("reasoning", "")
        messages: list[Any] | None = None
        try:
            if isinstance(prompt_payload, list):
                messages = prompt_payload
            elif isinstance(prompt_payload, str) and prompt_payload:
                try:
                    messages = json.loads(normalize_llm_text(prompt_payload))
                except json.JSONDecodeError:
                    messages = ast.literal_eval(normalize_llm_text(prompt_payload))
            if isinstance(messages, list):
                top_prompts_with_scores.append((messages, score, reasoning))
        except Exception:
            continue
    return top_prompts_with_scores


def _parse_synthesis_prompt_items(contents: list[Any]) -> list[dict[str, Any]]:
    prompt_items = []
    for response in contents:
        if not isinstance(response, PromptCandidatesResponse):
            raise TypeError("Expected a structured PromptCandidatesResponse")
        prompt_items.extend(response.model_dump()["prompts"])
    return prompt_items


def _build_synthesis_prompts(
    *,
    optimizer: Any,
    current_prompt: chat_prompt.ChatPrompt,
    round_num: int | None,
    prompt_items: list[dict[str, Any]],
) -> list[chat_prompt.ChatPrompt]:
    candidates = []
    for idx, item in enumerate(prompt_items, start=1):
        if not isinstance(item, dict) or not isinstance(item.get("edits"), list):
            raise ValueError("Candidate must contain a list of targeted text edits")
        candidate = apply_text_edits(
            current_prompt,
            item["edits"],
            getattr(optimizer, "_optimizable_roles", None),
        )
        candidates.append(candidate)
        optimizer._candidate_metadata_by_prompt_id[id(candidate)] = {
            "improvement_focus": item.get("improvement_focus"),
            "reasoning": item.get("reasoning"),
        }
        reporting.log_candidate_generated(
            round_num=round_num,
            candidate_id=f"round{round_num}_cand{idx}",
            prompt_messages=candidate.get_messages(),
            improvement_focus=item.get("improvement_focus"),
            reasoning=item.get("reasoning"),
        )
    return candidates


def generate_synthesis_prompts(
    optimizer: Any,
    current_prompt: chat_prompt.ChatPrompt,
    best_score: float,
    previous_rounds: Sequence[OptimizationRound],
    metric: MetricFunction,
    get_task_context_fn: Callable,
    optimization_id: str | None = None,
    project_name: str | None = None,
    round_num: int | None = None,
) -> list[chat_prompt.ChatPrompt]:
    """
    Generate synthesis prompts that combine top performers into comprehensive prompts.

    This is called every N rounds to prevent convergence to overly terse solutions
    by combining successful elements from multiple high-performing prompts.
    """
    num_synthesis_prompts = getattr(optimizer, "synthesis_prompts_per_round", 2)
    previous_rounds_list = list(previous_rounds)
    optimizer._candidate_metadata_by_prompt_id = getattr(
        optimizer, "_candidate_metadata_by_prompt_id", {}
    )

    with reporting.display_candidate_generation_report(
        num_synthesis_prompts,
        verbose=optimizer.verbose,
        selection_summary=display_utils.summarize_selection_policy(current_prompt),
    ) as candidate_generation_report:
        top_prompts_with_scores = _collect_hof_prompts(optimizer)

        if not top_prompts_with_scores:
            logger.warning("Hall of Fame empty - using recent rounds for synthesis")
            top_prompts_with_scores = _collect_recent_round_prompts(
                previous_rounds_list
            )

        if not top_prompts_with_scores:
            raise ValueError(
                "Cannot generate synthesis prompts no top performers available"
            )

        logger.info(
            "Synthesizing from %s top-performing prompts",
            len(top_prompts_with_scores),
        )

        task_context_str = ""
        if optimizer.enable_context:
            task_context_str, _ = get_task_context_fn(metric=metric)

        synthesis_template = optimizer.get_prompt("synthesis")
        reasoning_template = optimizer.get_prompt("reasoning_system")

        synthesis_user_prompt = meta_prompts.build_synthesis_prompt(
            template=synthesis_template,
            top_prompts_with_scores=[
                (text_only_messages(messages), score, reasoning)
                for messages, score, reasoning in top_prompts_with_scores
            ],
            task_context_str=task_context_str,
            best_score=best_score,
            num_prompts=num_synthesis_prompts,
        )
        synthesis_system_prompt = meta_prompts.build_reasoning_system_prompt(
            template=reasoning_template,
            allow_user_prompt_optimization=optimizer.allow_user_prompt_optimization,
        )

        try:
            metadata_for_call = _llm_calls.build_llm_call_metadata(
                optimizer, "optimization_algorithm_synthesis"
            )

            base_messages = [
                {"role": "system", "content": synthesis_system_prompt},
                {"role": "user", "content": synthesis_user_prompt},
            ]
            content = _llm_calls.call_model(
                evidence=ReasoningEvidence(
                    prompts={current_prompt.name: current_prompt}
                ),
                messages=base_messages,
                model=optimizer.model,
                model_parameters=optimizer.model_parameters,
                response_model=PromptCandidatesResponse,
                metadata=metadata_for_call,
                optimization_id=optimization_id,
                project_name=project_name,
                return_all=_llm_calls.requested_multiple_candidates(
                    optimizer.model_parameters
                ),
            )

            contents = content if isinstance(content, list) else [content]
            prompt_items = _parse_synthesis_prompt_items(contents)
            valid_prompts = _build_synthesis_prompts(
                optimizer=optimizer,
                current_prompt=current_prompt,
                round_num=round_num,
                prompt_items=prompt_items,
            )

            if not valid_prompts:
                raise ValueError("No valid synthesis prompts generated")

            candidate_generation_report.set_generated_prompts(len(valid_prompts))
            return valid_prompts

        except Exception as exc:
            if isinstance(exc, (BadRequestError, StructuredOutputParsingError)):
                raise
            raise ValueError(
                f"Unexpected error during synthesis prompt generation: {exc}"
            )
