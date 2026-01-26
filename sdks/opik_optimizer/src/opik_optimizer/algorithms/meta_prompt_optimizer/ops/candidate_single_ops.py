"""
Single-prompt candidate generation for the Meta-Prompt Optimizer.
"""

import copy
import json
import logging
import re
from typing import Any
from collections.abc import Callable, Sequence

from litellm.exceptions import BadRequestError

from ....api_objects import chat_prompt
from ....api_objects.types import MetricFunction
from ....core import llm_calls as _llm_calls
from ....core.llm_calls import StructuredOutputParsingError
from ....core.results import OptimizationRound
from ....utils import display as display_utils
from ....utils.text import normalize_llm_text
from .. import prompts as meta_prompts
from .. import reporting
from ..types import PromptCandidatesResponse

logger = logging.getLogger(__name__)
STRICT_JSON_INSTRUCTION = (
    "\n\nReturn ONLY valid JSON. Do not include commentary or markdown."
)


def _with_strict_json_instruction(
    messages: list[dict[str, str]],
) -> list[dict[str, str]]:
    updated: list[dict[str, str]] = []
    for msg in messages:
        if msg.get("role") == "user":
            updated.append(
                {
                    **msg,
                    "content": f"{msg.get('content', '')}{STRICT_JSON_INSTRUCTION}",
                }
            )
        else:
            updated.append(msg)
    return updated


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
    FORBIDDEN_PATTERNS = [
        metric_name.lower(),
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
        if "prompt" in prompt_item and isinstance(prompt_item["prompt"], list):
            has_leakage = False
            for message in prompt_item["prompt"]:
                content = message.get("content", "")
                content_lower = content.lower()
                for pattern in FORBIDDEN_PATTERNS:
                    if pattern in content_lower:
                        logger.warning(
                            "Detected data leakage in generated prompt: '%s' found in content: '%s...'",
                            pattern,
                            content[:100],
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
        current_prompt_messages=str(current_prompt.get_messages()),
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
        {"role": "user", "content": user_prompt},
    ]


def _parse_candidate_json(content_item: str) -> dict[str, Any]:
    try:
        return json.loads(content_item)
    except json.JSONDecodeError:
        json_match = re.search(r"\{.*\}", content_item, re.DOTALL)
        if not json_match:
            raise ValueError(
                "No JSON object found in response via regex. - received: %s"
                % content_item
            )
        try:
            return json.loads(normalize_llm_text(json_match.group()))
        except json.JSONDecodeError as exc:
            raise ValueError(
                "Could not parse JSON extracted via regex: %s - received: %s"
                % (exc, json_match.group())
            )


def _normalize_prompt_json(json_result: Any) -> dict[str, Any]:
    if isinstance(json_result, list):
        if (
            len(json_result) == 1
            and isinstance(json_result[0], dict)
            and "prompts" in json_result[0]
        ):
            return json_result[0]
        if all(isinstance(item, dict) and "prompt" in item for item in json_result):
            logger.debug("Received unwrapped prompt list, wrapping in 'prompts' key")
            return {"prompts": json_result}
    if not isinstance(json_result, dict) or "prompts" not in json_result:
        logger.debug("Parsed JSON content: %s", json_result)
        raise ValueError(
            "Parsed JSON is not a dictionary or missing 'prompts' key. - received: %s"
            % json_result
        )
    if not isinstance(json_result["prompts"], list):
        logger.debug("Content of 'prompts': %s", json_result.get("prompts"))
        raise ValueError(
            "'prompts' key does not contain a list. - received: %s"
            % json_result.get("prompts")
        )
    return json_result


def _parse_candidate_prompts(
    *,
    contents: list[Any],
    metric_name: str,
) -> list[dict[str, Any]]:
    prompt_items: list[dict[str, Any]] = []
    for content_item in contents:
        if hasattr(content_item, "model_dump"):
            normalized = content_item.model_dump()
        else:
            normalized = content_item
        if isinstance(normalized, str):
            normalized = normalize_llm_text(normalized)
            json_result = _parse_candidate_json(normalized)
        elif isinstance(normalized, dict):
            json_result = normalized
        else:
            raise ValueError(
                "Candidate generation response must be a string or dict; received %s"
                % type(normalized).__name__
            )
        json_result = _normalize_prompt_json(json_result)
        json_result = sanitize_generated_prompts(json_result, metric_name)
        prompt_items.extend(json_result["prompts"])
    return prompt_items


def _build_prompts_from_items(
    *,
    optimizer: Any,
    current_prompt: chat_prompt.ChatPrompt,
    round_num: int,
    prompt_items: list[dict[str, Any]],
) -> list[chat_prompt.ChatPrompt]:
    valid_prompts: list[chat_prompt.ChatPrompt] = []
    for idx, item in enumerate(prompt_items, start=1):
        if (
            isinstance(item, dict)
            and "prompt" in item
            and isinstance(item["prompt"], list)
        ):
            improvement_focus = item.get("improvement_focus")
            reasoning = item.get("reasoning")
            system_content = None
            user_content = None

            for msg in item["prompt"]:
                if msg.get("role") == "system":
                    system_content = msg.get("content", "")
                elif (
                    msg.get("role") == "user"
                    and optimizer.allow_user_prompt_optimization
                ):
                    user_content = msg.get("content", "")

            if user_content is None:
                if current_prompt.user:
                    user_content = current_prompt.user
                elif current_prompt.messages is not None:
                    user_content = current_prompt.messages[-1]["content"]
                else:
                    raise Exception("User content not found in chat-prompt!")

            if system_content is None:
                system_content = ""

            valid_prompts.append(
                chat_prompt.ChatPrompt(
                    name=current_prompt.name,
                    system=system_content,
                    user=user_content,
                    tools=current_prompt.tools,
                    function_map=current_prompt.function_map,
                    model=current_prompt.model,
                    model_parameters=current_prompt.model_kwargs,
                )
            )
            prompt_ref = valid_prompts[-1]
            optimizer._candidate_metadata_by_prompt_id[id(prompt_ref)] = {
                "improvement_focus": improvement_focus,
                "reasoning": reasoning,
            }
            reporting.log_candidate_generated(
                round_num=round_num,
                candidate_id=f"round{round_num}_cand{idx}",
                prompt_messages=prompt_ref.get_messages(),
                improvement_focus=improvement_focus,
                reasoning=reasoning,
            )
        else:
            logger.warning(
                "Skipping invalid prompt item structure in JSON response: %s", item
            )
    return valid_prompts


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

            try:
                content = _llm_calls.call_model(
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
            except (BadRequestError, StructuredOutputParsingError):
                retry_messages = _with_strict_json_instruction(messages)
                content = _llm_calls.call_model(
                    messages=retry_messages,
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
            contents = [
                normalize_llm_text(item) if isinstance(item, str) else item
                for item in contents
            ]
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
