"""
Single-prompt candidate generation for the Meta-Prompt Optimizer.
"""

import copy
import json
import logging
import random
from typing import Any
from collections.abc import Callable, Sequence

from litellm.exceptions import BadRequestError

from ....api_objects import chat_prompt
from ....api_objects.types import MetricFunction
from ....core import llm_calls as _llm_calls
from ....core.llm_calls import StructuredOutputParsingError
from ....core.results import OptimizationRound
from ....utils import display as display_utils
from ....utils.logging import compact_debug_text
from ....utils.text import normalize_llm_text
from .. import prompts as meta_prompts
from .. import reporting

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
        "squad",
        "naturalquestions",
        "dataset",
        "training data",
        "ground truth",
        "gold standard",
        "evaluation metric",
        "scoring function",
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
) -> list[chat_prompt.ChatPrompt]:
    """
    Generate candidate prompts using meta-prompting.
    """
    with reporting.display_candidate_generation_report(
        optimizer.prompts_per_round,
        verbose=optimizer.verbose,
        selection_summary=display_utils.summarize_selection_policy(current_prompt),
    ) as candidate_generation_report:
        logger.debug("\nGenerating candidate prompts for round %s", round_num + 1)
        logger.debug("Generating from prompt: %s", current_prompt.get_messages())
        logger.debug("Current best score: %.4f", best_score)

        pattern_guidance = ""
        if winning_patterns and random.random() < optimizer.pattern_injection_rate:
            pattern_guidance = "WINNING PATTERNS TO CONSIDER:\n"
            pattern_guidance += (
                "The following patterns have been successful in high-scoring prompts:\n"
            )
            for i, pattern in enumerate(winning_patterns, 1):
                pattern_guidance += f"{i}. {pattern}\n"
            pattern_guidance += (
                "\nConsider incorporating these patterns where appropriate, "
            )
            pattern_guidance += "but adapt them to fit the current prompt's needs."
            logger.info("Injecting %s patterns into generation", len(winning_patterns))

        history_context = build_history_context_fn(previous_rounds)
        task_context_str = ""
        analysis_instruction = ""
        metric_focus_instruction = ""

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
            analysis_instruction = (
                "Analyze the history of scores and the current prompt's performance."
            )
            metric_focus_instruction = (
                "Focus on generating diverse and effective prompt variations based on the history."
            )

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

        try:
            metadata_for_call = _llm_calls.build_llm_call_metadata(
                optimizer, "optimization_algorithm"
            )

            content = _llm_calls.call_model(
                messages=[
                    {
                        "role": "system",
                        "content": meta_prompts.build_reasoning_system_prompt(
                            template=reasoning_template,
                            allow_user_prompt_optimization=optimizer.allow_user_prompt_optimization,
                            mode="single",
                        ),
                    },
                    {"role": "user", "content": user_prompt},
                ],
                model=optimizer.model,
                model_parameters=optimizer.model_parameters,
                return_all=_llm_calls.requested_multiple_candidates(
                    optimizer.model_parameters
                ),
                metadata=metadata_for_call,
                optimization_id=optimization_id,
                project_name=project_name,
            )
            contents = content if isinstance(content, list) else [content]
            contents = [
                normalize_llm_text(item) if isinstance(item, str) else item
                for item in contents
            ]
            if logger.isEnabledFor(logging.DEBUG):
                cleaned = [
                    compact_debug_text(item) if isinstance(item, str) else item
                    for item in contents
                ]
                logger.debug("Raw response from reasoning model: %s", cleaned)

            valid_prompts: list[chat_prompt.ChatPrompt] = []
            metric_name = metric.__name__

            for content_item in contents:
                json_result = None
                try:
                    json_result = json.loads(normalize_llm_text(content_item))
                except json.JSONDecodeError:
                    import re

                    json_match = re.search(r"\{.*\}", content_item, re.DOTALL)
                    if json_match:
                        try:
                            json_result = json.loads(
                                normalize_llm_text(json_match.group())
                            )
                        except json.JSONDecodeError as e:
                            raise ValueError(
                                "Could not parse JSON extracted via regex: %s - received: %s"
                                % (e, json_match.group())
                            )
                    else:
                        raise ValueError(
                            "No JSON object found in response via regex. - received: %s"
                            % content_item
                        )

                if isinstance(json_result, list):
                    if (
                        len(json_result) == 1
                        and isinstance(json_result[0], dict)
                        and "prompts" in json_result[0]
                    ):
                        json_result = json_result[0]
                    elif all(
                        isinstance(item, dict) and "prompt" in item
                        for item in json_result
                    ):
                        logger.debug(
                            "Received unwrapped prompt list, wrapping in 'prompts' key"
                        )
                        json_result = {"prompts": json_result}

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

                json_result = sanitize_generated_prompts(json_result, metric_name)

                for item in json_result["prompts"]:
                    if (
                        isinstance(item, dict)
                        and "prompt" in item
                        and isinstance(item["prompt"], list)
                    ):
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
                            else:
                                if current_prompt.messages is not None:
                                    user_content = current_prompt.messages[-1][
                                        "content"
                                    ]
                                else:
                                    raise Exception(
                                        "User content not found in chat-prompt!"
                                    )

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

                        focus = item.get("improvement_focus", "N/A")
                        reasoning = item.get("reasoning", "N/A")
                        logger.debug("Generated prompt: %s", item["prompt"])
                        logger.debug("  Improvement focus: %s", focus)
                        logger.debug("  Reasoning: %s", reasoning)
                    else:
                        logger.warning(
                            "Skipping invalid prompt item structure in JSON response: %s",
                            item,
                        )

            if not valid_prompts:
                raise ValueError(
                    "No valid prompts found in the parsed JSON response after validation."
                )

            candidate_generation_report.set_generated_prompts()

            return valid_prompts

        except Exception as e:
            if isinstance(e, (BadRequestError, StructuredOutputParsingError)):
                raise
            raise ValueError(
                f"Unexpected error during candidate prompt generation: {e}"
            )
