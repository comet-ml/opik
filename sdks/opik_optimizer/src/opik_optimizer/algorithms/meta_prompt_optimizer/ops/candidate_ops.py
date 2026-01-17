"""
Candidate generation operations for the Meta-Prompt Optimizer.

This module contains functions for generating and sanitizing candidate prompts.
"""

import ast
import copy
from typing import Any
from collections.abc import Sequence
from collections.abc import Callable
import logging
import json
import random

from ....api_objects import chat_prompt
from ....api_objects.types import MetricFunction
from .... import _llm_calls
from ....optimization_result import OptimizationRound
from .. import prompts as meta_prompts
from .. import reporting
from ..types import (
    AgentBundleCandidatesResponse,
    AgentMetadata,
    AgentBundleCandidate,
)
from ....utils import display as display_utils
from ....utils.logging import compact_debug_text
from ....utils.text import normalize_llm_text
from litellm.exceptions import BadRequestError
from ...._llm_calls import StructuredOutputParsingError

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
    # Common patterns that indicate data leakage
    FORBIDDEN_PATTERNS = [
        # Metric-specific terms
        metric_name.lower(),
        "f1 score",
        "f1-score",
        "token-level",
        "exact match",
        "rogue",
        "bleu",
        "meteor",
        # Dataset-specific terms
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
        # Evaluation-specific terms
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

            # Check each message in the prompt
            for message in prompt_item["prompt"]:
                content = message.get("content", "")
                content_lower = content.lower()

                # Check for forbidden patterns
                for pattern in FORBIDDEN_PATTERNS:
                    if pattern in content_lower:
                        logger.warning(
                            f"Detected data leakage in generated prompt: '{pattern}' "
                            f"found in content: '{content[:100]}...'"
                        )
                        has_leakage = True
                        break

                if has_leakage:
                    break

            # Mark prompt as rejected if leakage detected
            if has_leakage:
                prompt_item["_rejected"] = True
                rejected_count += 1

    # Filter out rejected prompts
    original_count = len(sanitized.get("prompts", []))
    sanitized["prompts"] = [
        p for p in sanitized["prompts"] if not p.get("_rejected", False)
    ]

    if rejected_count > 0:
        logger.info(
            f"Sanitization: Rejected {rejected_count}/{original_count} prompts "
            f"due to data leakage"
        )

    return sanitized


def _format_agent_prompts_for_prompt(
    agent_prompts: dict[str, chat_prompt.ChatPrompt],
) -> str:
    """
    Render named chat prompts into a string block for the meta-prompt.
    """
    blocks: list[str] = []
    for agent_name, prompt in agent_prompts.items():
        messages = prompt.get_messages()
        blocks.append(
            f"Agent name: {agent_name}\nMessages:\n{json.dumps(messages, indent=2)}"
        )
    return "\n\n".join(blocks)


def generate_candidate_prompts(
    optimizer: Any,
    current_prompt: chat_prompt.ChatPrompt,
    best_score: float,
    round_num: int,
    previous_rounds: Sequence[OptimizationRound | dict[str, Any]],
    metric: MetricFunction,
    build_history_context_fn: Callable,
    get_task_context_fn: Callable,
    optimization_id: str | None = None,
    project_name: str | None = None,
    winning_patterns: list[str] | None = None,
) -> list[chat_prompt.ChatPrompt]:
    """
    Generate candidate prompts using meta-prompting.

    Args:
        optimizer: Reference to the optimizer instance
        current_prompt: Current best prompt
        best_score: Current best score
        round_num: Current round number
        previous_rounds: List of previous optimization rounds
        metric: Metric function
        build_history_context_fn: Function to build history context
        get_task_context_fn: Function to get task context
        optimization_id: Optional optimization ID
        project_name: Optional project name
        winning_patterns: Optional list of winning patterns to inject

    Returns:
        List of candidate prompts
    """
    with reporting.display_candidate_generation_report(
        optimizer.prompts_per_round,
        verbose=optimizer.verbose,
        selection_summary=display_utils.summarize_selection_policy(current_prompt),
    ) as candidate_generation_report:
        logger.debug(f"\nGenerating candidate prompts for round {round_num + 1}")
        logger.debug(f"Generating from prompt: {current_prompt.get_messages()}")
        logger.debug(f"Current best score: {best_score:.4f}")

        # Prepare pattern injection guidance
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
            logger.info(f"Injecting {len(winning_patterns)} patterns into generation")

        history_context = build_history_context_fn(previous_rounds)
        task_context_str = ""
        analysis_instruction = ""
        metric_focus_instruction = ""

        if optimizer.enable_context:
            task_context_str, _ = get_task_context_fn(metric=metric)
            analysis_instruction = "Analyze the example provided (if any), the metric description (if any), and the history of scores."
            metric_focus_instruction = (
                "Focus on improving the score for the evaluation metric."
            )
            logger.debug(
                "Task context and metric-specific instructions enabled for reasoning prompt."
            )
        else:
            analysis_instruction = (
                "Analyze the history of scores and the current prompt's performance."
            )
            metric_focus_instruction = "Focus on generating diverse and effective prompt variations based on the history."
            logger.debug(
                "Task context and metric-specific instructions disabled for reasoning prompt."
            )

        # Get templates from optimizer (with potential overrides)
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
            # Prepare metadata for optimization algorithm call
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
                # Robust JSON Parsing and Validation
                json_result = None
                try:
                    # Try direct JSON parsing
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
                                f"Could not parse JSON extracted via regex: {e} - received: {json_match.group()}"
                            )
                    else:
                        raise ValueError(
                            f"No JSON object found in response via regex. - received: {content_item}"
                        )

                # Validate the parsed JSON structure
                if isinstance(json_result, list):
                    # Check if it's a wrapped format: [{"prompts": [...]}]
                    if (
                        len(json_result) == 1
                        and isinstance(json_result[0], dict)
                        and "prompts" in json_result[0]
                    ):
                        json_result = json_result[0]
                    # Check if it's unwrapped: [{prompt: ..., improvement_focus: ..., reasoning: ...}, ...]
                    elif all(
                        isinstance(item, dict) and "prompt" in item
                        for item in json_result
                    ):
                        logger.debug(
                            "Received unwrapped prompt list, wrapping in 'prompts' key"
                        )
                        json_result = {"prompts": json_result}

                if not isinstance(json_result, dict) or "prompts" not in json_result:
                    logger.debug(f"Parsed JSON content: {json_result}")
                    raise ValueError(
                        f"Parsed JSON is not a dictionary or missing 'prompts' key. - received: {json_result}"
                    )

                if not isinstance(json_result["prompts"], list):
                    logger.debug(f"Content of 'prompts': {json_result.get('prompts')}")
                    raise ValueError(
                        f"'prompts' key does not contain a list. - received: {json_result.get('prompts')}"
                    )

                # Sanitize generated prompts to remove data leakage
                json_result = sanitize_generated_prompts(json_result, metric_name)

                # Extract and log valid prompts
                for item in json_result["prompts"]:
                    if (
                        isinstance(item, dict)
                        and "prompt" in item
                        and isinstance(item["prompt"], list)
                    ):
                        # Extract system and user prompts from generated messages
                        system_content = None
                        user_content = None

                        for msg in item["prompt"]:
                            if msg.get("role") == "system":
                                system_content = msg.get("content", "")
                            elif (
                                msg.get("role") == "user"
                                and optimizer.allow_user_prompt_optimization
                            ):
                                # Only extract user content if optimization is allowed
                                user_content = msg.get("content", "")

                        # Always fall back to original user prompt if not extracted
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

                        # Use system from generated prompt, or empty string if not provided
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

                        # Log details
                        focus = item.get("improvement_focus", "N/A")
                        reasoning = item.get("reasoning", "N/A")
                        logger.debug(f"Generated prompt: {item['prompt']}")
                        logger.debug(f"  Improvement focus: {focus}")
                        logger.debug(f"  Reasoning: {reasoning}")
                    else:
                        logger.warning(
                            f"Skipping invalid prompt item structure in JSON response: {item}"
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


def generate_agent_bundle_candidates(
    optimizer: Any,
    current_prompts: dict[str, chat_prompt.ChatPrompt],
    best_score: float,
    round_num: int,
    previous_rounds: Sequence[OptimizationRound | dict[str, Any]],
    metric: MetricFunction,
    build_history_context_fn: Callable,
    get_task_context_fn: Callable,
    optimization_id: str | None = None,
    project_name: str | None = None,
    winning_patterns: list[str] | None = None,
) -> list[AgentBundleCandidate]:
    """
    Generate updated prompts for multiple named agents in a single meta-prompt pass.

    Returns:
        List of AgentBundleCandidate objects, each containing updated prompts
        and strongly-typed metadata for all agents in the bundle.
    """
    with reporting.display_candidate_generation_report(
        optimizer.prompts_per_round,
        verbose=optimizer.verbose,
        selection_summary=display_utils.summarize_selection_policy(current_prompts),
    ) as candidate_generation_report:
        logger.debug(f"\nGenerating agent bundle prompts for round {round_num + 1}")
        logger.debug("Generating from agents: %s", list(current_prompts.keys()))
        logger.debug(f"Current best score: {best_score:.4f}")

        pattern_guidance = ""
        if winning_patterns and random.random() < optimizer.pattern_injection_rate:
            pattern_guidance = "WINNING PATTERNS TO CONSIDER:\n"
            pattern_guidance += (
                "The following patterns have been successful in high-scoring prompts:\n"
            )
            for i, pattern in enumerate(winning_patterns, 1):
                pattern_guidance += f"{i}. {pattern}\n"
            pattern_guidance += "\nAdapt these patterns per agent where appropriate."
            logger.info(f"Injecting {len(winning_patterns)} patterns into generation")

        history_context = build_history_context_fn(previous_rounds)
        task_context_str = ""
        analysis_instruction = ""
        metric_focus_instruction = ""

        if optimizer.enable_context:
            task_context_str, _ = get_task_context_fn(metric=metric)
            analysis_instruction = "Analyze the examples/feedback (if any), metric description, and score history."
            metric_focus_instruction = "Focus on improving evaluation scores while keeping each agent's role distinct."
        else:
            analysis_instruction = (
                "Analyze score history and each agent's role before proposing changes."
            )
            metric_focus_instruction = "Generate effective, role-appropriate updates."

        agent_blocks = _format_agent_prompts_for_prompt(current_prompts)

        # Get templates from optimizer (with potential overrides)
        candidate_gen_template = optimizer.get_prompt("candidate_generation")
        reasoning_template = optimizer.get_prompt("reasoning_system")

        user_prompt = meta_prompts.build_candidate_generation_user_prompt(
            template=candidate_gen_template,
            current_prompt_messages="",  # unused in bundle mode
            best_score=best_score,
            history_context=history_context,
            task_context_str=task_context_str,
            analysis_instruction=analysis_instruction,
            metric_focus_instruction=metric_focus_instruction,
            prompts_per_round=optimizer.prompts_per_round,
            pattern_guidance=pattern_guidance,
            mode="bundle",
            agent_blocks=agent_blocks,
        )

        try:
            metadata_for_call = _llm_calls.build_llm_call_metadata(
                optimizer, "optimization_algorithm"
            )

            response = _llm_calls.call_model(
                messages=[
                    {
                        "role": "system",
                        "content": meta_prompts.build_reasoning_system_prompt(
                            template=reasoning_template,
                            allow_user_prompt_optimization=optimizer.allow_user_prompt_optimization,
                            mode="bundle",
                        ),
                    },
                    {"role": "user", "content": user_prompt},
                ],
                model=optimizer.model,
                model_parameters=optimizer.model_parameters,
                metadata=metadata_for_call,
                optimization_id=optimization_id,
                project_name=project_name,
                return_all=_llm_calls.requested_multiple_candidates(
                    optimizer.model_parameters
                ),
                response_model=AgentBundleCandidatesResponse,
            )

            responses = response if isinstance(response, list) else [response]

            candidates: list[AgentBundleCandidate] = []
            for response_item in responses:
                # Log summary of candidates
                logger.debug(
                    "Bundle LLM response: %d candidate bundles",
                    len(response_item.candidates),
                )
                for idx, cand in enumerate(response_item.candidates, start=1):
                    agents = [a.name for a in cand.agents]
                    focus = cand.bundle_improvement_focus
                    logger.debug(
                        "  Candidate %d: agents=%s focus=%s",
                        idx,
                        agents,
                        (focus[:120] + "...")
                        if isinstance(focus, str) and len(focus) > 120
                        else focus,
                    )

                for candidate_response in response_item.candidates:
                    updated_prompts: dict[str, chat_prompt.ChatPrompt] = {}
                    agent_metadata: dict[str, AgentMetadata] = {}

                    for agent_update in candidate_response.agents:
                        name = agent_update.name

                        if name not in current_prompts:
                            logger.warning(
                                "Received update for unknown agent '%s'; skipping.",
                                name,
                            )
                            continue

                        try:
                            # Convert Pydantic Message objects to dicts for ChatPrompt
                            messages_dict = [
                                msg.model_dump() for msg in agent_update.messages
                            ]
                            updated_prompt = chat_prompt.ChatPrompt(
                                name=current_prompts[name].name or name,
                                messages=messages_dict,
                                tools=current_prompts[name].tools,
                                function_map=current_prompts[name].function_map,
                                model=current_prompts[name].model,
                                model_parameters=current_prompts[name].model_kwargs,
                            )
                            updated_prompts[name] = updated_prompt
                            agent_metadata[name] = AgentMetadata(
                                improvement_focus=agent_update.improvement_focus,
                                reasoning=agent_update.reasoning,
                            )
                        except Exception as exc:
                            logger.warning(
                                "Failed to build ChatPrompt for agent '%s': %s",
                                name,
                                exc,
                            )

                    # Preserve any agents that were not returned to avoid losing prompts
                    for name, prompt in current_prompts.items():
                        if name not in updated_prompts:
                            updated_prompts[name] = prompt

                    if updated_prompts:
                        candidates.append(
                            AgentBundleCandidate(
                                prompts=updated_prompts, metadata=agent_metadata
                            )
                        )

            if not candidates:
                raise ValueError("No valid agent prompts returned from response.")

            candidate_generation_report.set_generated_prompts()
            return candidates

        except Exception as e:
            if isinstance(e, (BadRequestError, StructuredOutputParsingError)):
                raise
            raise ValueError(
                f"Unexpected error during agent bundle prompt generation: {e}"
            )


def generate_synthesis_prompts(
    optimizer: Any,
    current_prompt: chat_prompt.ChatPrompt,
    best_score: float,
    previous_rounds: Sequence[OptimizationRound | dict[str, Any]],
    metric: MetricFunction,
    get_task_context_fn: Callable,
    optimization_id: str | None = None,
    project_name: str | None = None,
) -> list[chat_prompt.ChatPrompt]:
    """
    Generate synthesis prompts that combine top performers into comprehensive prompts.

    This is called every N rounds to prevent convergence to overly terse solutions
    by combining successful elements from multiple high-performing prompts.

    Args:
        optimizer: Reference to the optimizer instance
        current_prompt: Current best prompt
        best_score: Current best score
        previous_rounds: List of previous optimization rounds
        metric: Metric function
        get_task_context_fn: Function to get task context
        optimization_id: Optional optimization ID
        project_name: Optional project name

    Returns:
        List of comprehensive synthesis prompts
    """
    num_synthesis_prompts = getattr(optimizer, "synthesis_prompts_per_round", 2)
    previous_rounds_list = list(previous_rounds)

    with reporting.display_candidate_generation_report(
        num_synthesis_prompts,
        verbose=optimizer.verbose,  # Synthesis generates a small number of prompts
        selection_summary=display_utils.summarize_selection_policy(current_prompt),
    ) as candidate_generation_report:
        # Get top performers from Hall of Fame
        top_prompts_with_scores: list[tuple[list[dict[str, str]], float, str]] = []

        if optimizer.hall_of_fame and hasattr(optimizer.hall_of_fame, "entries"):
            # Get top 3-5 entries from Hall of Fame
            for entry in optimizer.hall_of_fame.entries[:5]:
                prompt_messages = entry.prompt_messages
                score = entry.score

                # Build comprehensive reasoning with all available context
                reasoning_parts = []
                reasoning_parts.append(f"Trial #{entry.trial_number}")
                reasoning_parts.append(
                    f"Improvement: {entry.improvement_over_baseline * 100:+.1f}% over baseline"
                )

                # Include extracted patterns if available
                if entry.extracted_patterns:
                    reasoning_parts.append(
                        f"Winning patterns: {' | '.join(entry.extracted_patterns)}"
                    )

                # Include metadata if available
                if entry.metadata:
                    for key, value in entry.metadata.items():
                        if value and key not in ["prompt_messages", "score"]:
                            reasoning_parts.append(f"{key}: {value}")

                reasoning = " | ".join(reasoning_parts)
                top_prompts_with_scores.append((prompt_messages, score, reasoning))

        # Fallback: if Hall of Fame is empty or not available, use recent rounds
        if not top_prompts_with_scores:
            logger.warning("Hall of Fame empty - using recent rounds for synthesis")
            # Collect best prompts from recent rounds
            for round_data in reversed(previous_rounds_list[-5:]):
                if not isinstance(round_data, dict):
                    try:
                        round_data = round_data.to_dict()  # type: ignore[attr-defined]
                    except Exception:
                        continue
                generated = round_data.get("generated_prompts", [])
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
                    # Try to parse as messages
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
            f"Synthesizing from {len(top_prompts_with_scores)} top-performing prompts"
        )

        # Get task context
        task_context_str = ""
        if optimizer.enable_context:
            task_context_str, _ = get_task_context_fn(metric=metric)  # Unpack tuple

        # Get templates from optimizer (with potential overrides)
        synthesis_template = optimizer.get_prompt("synthesis")
        reasoning_template = optimizer.get_prompt("reasoning_system")

        # Build synthesis prompt
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
            # Prepare metadata for synthesis call
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
                # Parse JSON response
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
                                f"Could not parse synthesis JSON: {e} - received: {json_match.group()}"
                            )
                    else:
                        raise ValueError(
                            f"No JSON object found in synthesis response: {content_item}"
                        )

                # Validate structure - handle both wrapped and unwrapped formats
                if isinstance(json_result, list):
                    # Check if it's a wrapped format: [{"prompts": [...]}]
                    if (
                        len(json_result) == 1
                        and isinstance(json_result[0], dict)
                        and "prompts" in json_result[0]
                    ):
                        json_result = json_result[0]
                    # Check if it's unwrapped: [{prompt: ..., improvement_focus: ..., reasoning: ...}, ...]
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

                # Extract synthesis prompts (expecting 1-2)
                for item in json_result["prompts"]:
                    if (
                        isinstance(item, dict)
                        and "prompt" in item
                        and isinstance(item["prompt"], list)
                    ):
                        # Get user text from current prompt
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

                        # Log synthesis details
                        focus = item.get("improvement_focus", "N/A")
                        reasoning = item.get("reasoning", "N/A")
                        logger.info("Generated synthesis prompt:")
                        logger.info(f"  Improvement focus: {focus}")
                        logger.info(f"  Reasoning: {reasoning}")
                        logger.debug(f"  Full prompt: {item['prompt']}")

            if not valid_prompts:
                raise ValueError("No valid synthesis prompts generated")

            candidate_generation_report.set_generated_prompts()

            return valid_prompts

        except Exception as e:
            if isinstance(e, (BadRequestError, StructuredOutputParsingError)):
                raise
            raise ValueError(
                f"Unexpected error during synthesis prompt generation: {e}"
            )


def generate_round_candidates(
    *,
    optimizer: Any,
    best_prompts: dict[str, chat_prompt.ChatPrompt],
    best_score: float,
    round_num: int,
    previous_rounds: Sequence[OptimizationRound | dict[str, Any]],
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
            )
            prompt_key = next(iter(best_prompts.keys()))
            candidate_prompts = [
                {prompt_key: prompt}
                for prompt in single_candidates[:prompts_this_round]
            ]
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
            )
            candidate_prompts = [
                bundle.prompts for bundle in bundle_candidates[:prompts_this_round]
            ]

        synthesis_candidates: list[dict[str, chat_prompt.ChatPrompt]] = []
        if (
            is_single_prompt_optimization
            and optimizer.synthesis_prompts_per_round > 0
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
            )
            prompt_key = next(iter(best_prompts.keys()))
            synthesis_candidates = [
                {prompt_key: prompt} for prompt in synthesis_prompts
            ]

        if synthesis_candidates:
            candidate_prompts = synthesis_candidates + candidate_prompts
            candidate_prompts = candidate_prompts[:prompts_this_round]

        return candidate_prompts
    except Exception as exc:
        if isinstance(exc, (BadRequestError, StructuredOutputParsingError)):
            raise
        logger.warning("Failed to generate %s candidates: %s", prompts_this_round, exc)
        return []
