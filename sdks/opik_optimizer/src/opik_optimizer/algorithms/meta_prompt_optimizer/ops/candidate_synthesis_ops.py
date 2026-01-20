"""
Synthesis candidate generation for the Meta-Prompt Optimizer.
"""

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
from .. import prompts as meta_prompts
from .. import reporting

logger = logging.getLogger(__name__)


def generate_synthesis_prompts(
    optimizer: Any,
    current_prompt: chat_prompt.ChatPrompt,
    best_score: float,
    previous_rounds: Sequence[OptimizationRound],
    metric: MetricFunction,
    get_task_context_fn: Callable,
    optimization_id: str | None = None,
    project_name: str | None = None,
) -> list[chat_prompt.ChatPrompt]:
    """
    Generate synthesis prompts that combine top performers into comprehensive prompts.

    This is called every N rounds to prevent convergence to overly terse solutions
    by combining successful elements from multiple high-performing prompts.
    """
    num_synthesis_prompts = getattr(optimizer, "synthesis_prompts_per_round", 2)
    previous_rounds_list = list(previous_rounds)

    with reporting.display_candidate_generation_report(
        num_synthesis_prompts,
        verbose=optimizer.verbose,
        selection_summary=display_utils.summarize_selection_policy(current_prompt),
    ) as candidate_generation_report:
        top_prompts_with_scores: list[tuple[list[dict[str, str]], float, str]] = []

        if optimizer.hall_of_fame and hasattr(optimizer.hall_of_fame, "entries"):
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

        if not top_prompts_with_scores:
            logger.warning("Hall of Fame empty - using recent rounds for synthesis")
            for round_data in reversed(previous_rounds_list[-5:]):
                generated = round_payload(round_data).get("generated_prompts", [])
                sorted_generated = sorted(
                    generated,
                    key=lambda p: p.get("score", -float("inf")),
                    reverse=True,
                )
                if sorted_generated:
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
                                messages = json.loads(
                                    normalize_llm_text(prompt_payload)
                                )
                            except json.JSONDecodeError:
                                messages = ast.literal_eval(
                                    normalize_llm_text(prompt_payload)
                                )
                        if isinstance(messages, list):
                            top_prompts_with_scores.append((messages, score, reasoning))
                    except Exception:
                        continue

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
            top_prompts_with_scores=top_prompts_with_scores,
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

            content = _llm_calls.call_model(
                messages=[
                    {"role": "system", "content": synthesis_system_prompt},
                    {"role": "user", "content": synthesis_user_prompt},
                ],
                model=optimizer.model,
                model_parameters=optimizer.model_parameters,
                metadata=metadata_for_call,
                optimization_id=optimization_id,
                project_name=project_name,
                return_all=_llm_calls.requested_multiple_candidates(
                    optimizer.model_parameters
                ),
            )

            contents = content if isinstance(content, list) else [content]
            valid_prompts: list[chat_prompt.ChatPrompt] = []

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
                        except json.JSONDecodeError as exc:
                            raise ValueError(
                                f"Could not parse synthesis JSON: {exc} - received: {json_match.group()}"
                            )
                    else:
                        raise ValueError(
                            f"No JSON object found in synthesis response: {content_item}"
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
                        json_result = {"prompts": json_result}

                if not isinstance(json_result, dict) or "prompts" not in json_result:
                    raise ValueError(
                        f"Invalid synthesis JSON structure - received: {json_result}"
                    )

                if not isinstance(json_result["prompts"], list):
                    raise ValueError(
                        "'prompts' key does not contain a list in synthesis response"
                    )

                for item in json_result["prompts"]:
                    if (
                        isinstance(item, dict)
                        and "prompt" in item
                        and isinstance(item["prompt"], list)
                    ):
                        if current_prompt.user:
                            user_text = current_prompt.user
                        else:
                            if current_prompt.messages is not None:
                                user_text = current_prompt.messages[-1]["content"]
                            else:
                                raise Exception(
                                    "User content not found in chat-prompt!"
                                )

                        valid_prompts.append(
                            chat_prompt.ChatPrompt(
                                name=current_prompt.name,
                                system=item["prompt"][0]["content"],
                                user=user_text,
                                tools=current_prompt.tools,
                                function_map=current_prompt.function_map,
                                model=current_prompt.model,
                                model_parameters=current_prompt.model_kwargs,
                            )
                        )

                        focus = item.get("improvement_focus", "N/A")
                        reasoning = item.get("reasoning", "N/A")
                        logger.info("Generated synthesis prompt:")
                        logger.info("  Improvement focus: %s", focus)
                        logger.info("  Reasoning: %s", reasoning)
                        logger.debug("  Full prompt: %s", item["prompt"])

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
