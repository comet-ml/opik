"""
Single-prompt candidate generation for the Meta-Prompt Optimizer.
"""

from ....utils.prompt_editing import apply_text_edits

from ....core.reasoning import ReasoningEvidence

import copy
import logging
from typing import Any
from collections.abc import Callable, Sequence

from litellm.exceptions import BadRequestError

from ....api_objects import chat_prompt
from ....api_objects.types import MetricFunction, extract_text_from_content
from ....core import llm_calls as _llm_calls
from ....core.llm_calls import StructuredOutputParsingError
from ....core.results import OptimizationRound
from ....utils import display as display_utils
from ....utils.multimodal import text_only_messages
from .. import prompts as meta_prompts
from .. import reporting
from ..types import PromptCandidatesResponse

logger = logging.getLogger(__name__)


def sanitize_generated_prompts(
    prompt_json: dict[str, Any], metric_name: str
) -> dict[str, Any]:
    """
    Remove any leaked dataset/metric references from generated prompts.

    Args:
        prompt_json: JSON dict containing generated prompts
        metric_name: Name of the metric being optimized

    Returns:
        Sanitized prompt_json with rejected prompts removed
    """
    metric = metric_name.strip().lower()
    metric_patterns = (
        [f"{metric} metric", f"{metric} score", f"optimize for {metric}", f"{metric}("]
        if metric
        else []
    )
    # Function-style names are distinctive; ordinary words such as "exact" are not.
    if "_" in metric or "-" in metric:
        metric_patterns.append(metric)
    FORBIDDEN_PATTERNS = [
        *metric_patterns,
        "f1 score",
        "f1-score",
        "token-level",
        "exact match",
        "rogue",
        "bleu",
        "meteor",
        "supporting_facts",
        "supporting facts",
        "answer field",
        "context field",
        "question field",
        "hotpotqa",
        "training data",
        "ground truth",
        "gold standard",
        "evaluation metric",
        "scoring function",
        "summary_1",
        "passages_1",
        "passages_2",
        "gaps_1",
        "n_samples",
    ]

    sanitized = copy.deepcopy(prompt_json)
    rejected_count = 0
    for prompt_item in sanitized.get("prompts", []):
        if "edits" in prompt_item and isinstance(prompt_item["edits"], list):
            has_leakage = False
            for message in prompt_item["edits"]:
                content = message.get("text", "")
                # Extract text from content (handles both string and multimodal list content)
                text_content = extract_text_from_content(content)
                content_lower = text_content.lower()
                for pattern in FORBIDDEN_PATTERNS:
                    if pattern in content_lower:
                        # Format content for logging
                        content_preview = (
                            text_content[:100] if text_content else str(content)[:100]
                        )
                        logger.warning(
                            "Detected data leakage in generated prompt: '%s' found in content: '%s...'",
                            pattern,
                            content_preview,
                        )
                        has_leakage = True
                        break
                if has_leakage:
                    break
            if has_leakage:
                prompt_item["_rejected"] = True
                rejected_count += 1

    original_count = len(sanitized.get("prompts", []))
    sanitized["prompts"] = [
        p for p in sanitized["prompts"] if not p.get("_rejected", False)
    ]

    if rejected_count > 0:
        logger.info(
            "Sanitization: Rejected %s/%s prompts due to data leakage",
            rejected_count,
            original_count,
        )

    return sanitized


def _build_pattern_guidance(
    optimizer: Any,
    winning_patterns: list[str] | None,
    rng: Any,
) -> str:
    if not winning_patterns or rng.random() >= optimizer.pattern_injection_rate:
        return ""
    pattern_guidance = "WINNING PATTERNS TO CONSIDER:\n"
    pattern_guidance += (
        "The following patterns have been successful in high-scoring prompts:\n"
    )
    for i, pattern in enumerate(winning_patterns, 1):
        pattern_guidance += f"{i}. {pattern}\n"
    pattern_guidance += "\nConsider incorporating these patterns where appropriate, "
    pattern_guidance += "but adapt them to fit the current prompt's needs."
    reporting.log_pattern_injection(winning_patterns)
    return pattern_guidance


def _build_context_instructions(
    optimizer: Any,
    metric: MetricFunction,
    get_task_context_fn: Callable,
) -> tuple[str, str, str]:
    if optimizer.enable_context:
        task_context_str, _ = get_task_context_fn(metric=metric)
        analysis_instruction = (
            "Analyze the example provided (if any), the metric description (if any), "
            "and the history of scores."
        )
        metric_focus_instruction = (
            "Focus on improving the score for the evaluation metric."
        )
    else:
        task_context_str = ""
        analysis_instruction = (
            "Analyze the history of scores and the current prompt's performance."
        )
        metric_focus_instruction = "Focus on generating diverse and effective prompt variations based on the history."
    return task_context_str, analysis_instruction, metric_focus_instruction


def _build_generation_messages(
    *,
    optimizer: Any,
    current_prompt: chat_prompt.ChatPrompt,
    best_score: float,
    history_context: str,
    task_context_str: str,
    analysis_instruction: str,
    metric_focus_instruction: str,
    pattern_guidance: str,
) -> list[dict[str, str]]:
    candidate_gen_template = optimizer.get_prompt("candidate_generation")
    reasoning_template = optimizer.get_prompt("reasoning_system")
    user_prompt = meta_prompts.build_candidate_generation_user_prompt(
        template=candidate_gen_template,
        current_prompt_messages=str(text_only_messages(current_prompt.get_messages())),
        best_score=best_score,
        history_context=history_context,
        task_context_str=task_context_str,
        analysis_instruction=analysis_instruction,
        metric_focus_instruction=metric_focus_instruction,
        prompts_per_round=optimizer.prompts_per_round,
        pattern_guidance=pattern_guidance,
        mode="single",
        agent_blocks=None,
    )
    return [
        {
            "role": "system",
            "content": meta_prompts.build_reasoning_system_prompt(
                template=reasoning_template,
                allow_user_prompt_optimization=optimizer.allow_user_prompt_optimization,
                mode="single",
            ),
        },
        {
            "role": "user",
            "content": user_prompt
            + "\nReturn targeted text edits using the supplied editable segment IDs. Do not return messages or media.",
        },
    ]


def _parse_candidate_prompts(
    *, contents: list[Any], metric_name: str
) -> list[dict[str, Any]]:
    prompt_items = []
    for response in contents:
        if not isinstance(response, PromptCandidatesResponse):
            raise TypeError("Expected a structured PromptCandidatesResponse")
        prompt_items.extend(
            sanitize_generated_prompts(response.model_dump(), metric_name)["prompts"]
        )
    return prompt_items


def _build_prompts_from_items(
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


def generate_candidate_prompts(
    optimizer: Any,
    current_prompt: chat_prompt.ChatPrompt,
    best_score: float,
    round_num: int,
    previous_rounds: Sequence[OptimizationRound],
    metric: MetricFunction,
    build_history_context_fn: Callable,
    get_task_context_fn: Callable,
    optimization_id: str | None = None,
    project_name: str | None = None,
    winning_patterns: list[str] | None = None,
    expected_count: int | None = None,
) -> list[chat_prompt.ChatPrompt]:
    """
    Generate candidate prompts using meta-prompting.
    """
    optimizer._candidate_metadata_by_prompt_id = getattr(
        optimizer, "_candidate_metadata_by_prompt_id", {}
    )
    with reporting.display_candidate_generation_report(
        expected_count or optimizer.prompts_per_round,
        verbose=optimizer.verbose,
        selection_summary=display_utils.summarize_selection_policy(current_prompt),
    ) as candidate_generation_report:
        reporting.log_generation_start(
            round_num=round_num,
            best_score=best_score,
            source=current_prompt,
        )

        candidate_rng = optimizer._derive_rng("candidate_generation", round_num)
        pattern_guidance = _build_pattern_guidance(
            optimizer, winning_patterns, candidate_rng
        )
        history_context = build_history_context_fn(previous_rounds)
        task_context_str, analysis_instruction, metric_focus_instruction = (
            _build_context_instructions(optimizer, metric, get_task_context_fn)
        )
        messages = _build_generation_messages(
            optimizer=optimizer,
            current_prompt=current_prompt,
            best_score=best_score,
            history_context=history_context,
            task_context_str=task_context_str,
            analysis_instruction=analysis_instruction,
            metric_focus_instruction=metric_focus_instruction,
            pattern_guidance=pattern_guidance,
        )

        try:
            metadata_for_call = _llm_calls.build_llm_call_metadata(
                optimizer, "optimization_algorithm"
            )

            content = _llm_calls.call_model(
                evidence=ReasoningEvidence(
                    prompts={current_prompt.name: current_prompt}
                ),
                messages=messages,
                model=optimizer.model,
                model_parameters=optimizer.model_parameters,
                response_model=PromptCandidatesResponse,
                return_all=_llm_calls.requested_multiple_candidates(
                    optimizer.model_parameters
                ),
                metadata=metadata_for_call,
                optimization_id=optimization_id,
                project_name=project_name,
            )
            contents: list[Any] = content if isinstance(content, list) else [content]
            metric_name = metric.__name__
            prompt_items = _parse_candidate_prompts(
                contents=contents,
                metric_name=metric_name,
            )
            valid_prompts = _build_prompts_from_items(
                optimizer=optimizer,
                current_prompt=current_prompt,
                round_num=round_num,
                prompt_items=prompt_items,
            )

            if not valid_prompts:
                raise ValueError(
                    "No valid prompts found in the parsed JSON response after validation."
                )

            candidate_generation_report.set_generated_prompts(len(valid_prompts))

            return valid_prompts

        except (BadRequestError, StructuredOutputParsingError):
            raise
