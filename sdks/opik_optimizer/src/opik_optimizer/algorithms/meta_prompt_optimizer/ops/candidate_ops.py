"""
Candidate generation operations for the Meta-Prompt Optimizer.

This module contains functions for generating and sanitizing candidate prompts.
"""

from typing import Any
from collections.abc import Callable
import logging
import json
import random

from ....api_objects import chat_prompt
from .... import _llm_calls
from ....base_optimizer import OptimizationRound
from ....utils.prompt_segments import (
    extract_prompt_segments,
    apply_segment_updates,
)
from ..prompts import (
    build_reasoning_system_prompt,
    build_candidate_generation_user_prompt,
    build_mcp_tool_description_user_prompt,
)
from .. import reporting
from litellm.exceptions import BadRequestError
from ...._llm_calls import StructuredOutputParsingError

logger = logging.getLogger(__name__)


def _sync_tool_description_in_system(prompt: chat_prompt.ChatPrompt) -> None:
    """
    Synchronize tool descriptions in the system message.

    Updates the system message to reflect changes in tool descriptions.
    """
    from ....mcp_utils.mcp import PROMPT_TOOL_HEADER, PROMPT_TOOL_FOOTER

    if not prompt.tools:
        return

    system_text = prompt.system or ""
    if PROMPT_TOOL_HEADER not in system_text or PROMPT_TOOL_FOOTER not in system_text:
        return

    start = system_text.index(PROMPT_TOOL_HEADER) + len(PROMPT_TOOL_HEADER)
    end = system_text.index(PROMPT_TOOL_FOOTER)
    middle_block = system_text[start:end]

    import re

    for tool in prompt.tools:
        tool_name = tool.get("function", {}).get("name", "")
        if not tool_name:
            continue

        description_text = tool.get("function", {}).get("description", "")
        pattern = rf"(-\s*{re.escape(tool_name)}:\s)(.*)"
        middle_block = re.sub(
            pattern,
            lambda match: f"{match.group(1)}{description_text}",
            middle_block,
            count=1,
        )

    new_system = system_text[:start] + middle_block + system_text[end:]
    prompt.system = new_system


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

    rejected_count = 0
    for prompt_item in prompt_json.get("prompts", []):
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
    original_count = len(prompt_json.get("prompts", []))
    prompt_json["prompts"] = [
        p for p in prompt_json["prompts"] if not p.get("_rejected", False)
    ]

    if rejected_count > 0:
        logger.info(
            f"Sanitization: Rejected {rejected_count}/{original_count} prompts "
            f"due to data leakage"
        )

    return prompt_json


def generate_candidate_prompts(
    optimizer: Any,
    current_prompt: chat_prompt.ChatPrompt,
    best_score: float,
    round_num: int,
    previous_rounds: list[OptimizationRound],
    metric: Callable,
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
        optimizer.prompts_per_round, verbose=optimizer.verbose
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
        improvement_point_1 = ""

        if optimizer.enable_context:
            task_context_str = get_task_context_fn(metric=metric)
            analysis_instruction = "Analyze the example provided (if any), the metric description (if any), and the history of scores."
            metric_focus_instruction = (
                "Focus on improving the score for the evaluation metric."
            )
            improvement_point_1 = (
                "1. Be more specific and clear about expectations based on the task."
            )
            logger.debug(
                "Task context and metric-specific instructions enabled for reasoning prompt."
            )
        else:
            analysis_instruction = (
                "Analyze the history of scores and the current prompt's performance."
            )
            metric_focus_instruction = "Focus on generating diverse and effective prompt variations based on the history."
            improvement_point_1 = (
                "1. Be more specific and clear about expectations based on the task."
            )
            logger.debug(
                "Task context and metric-specific instructions disabled for reasoning prompt."
            )

        user_prompt = build_candidate_generation_user_prompt(
            current_prompt_messages=str(current_prompt.get_messages()),
            best_score=best_score,
            history_context=history_context,
            task_context_str=task_context_str,
            analysis_instruction=analysis_instruction,
            metric_focus_instruction=metric_focus_instruction,
            improvement_point_1=improvement_point_1,
            prompts_per_round=optimizer.prompts_per_round,
            pattern_guidance=pattern_guidance,
        )

        try:
            # Prepare metadata for optimization algorithm call
            metadata_for_call: dict[str, Any] = {}
            if project_name:
                metadata_for_call["project_name"] = project_name
                metadata_for_call["opik"] = {"project_name": project_name}
            if optimization_id and "opik" in metadata_for_call:
                metadata_for_call["opik"]["optimization_id"] = optimization_id
            metadata_for_call["optimizer_name"] = optimizer.__class__.__name__
            metadata_for_call["opik_call_type"] = "optimization_algorithm"

            content = _llm_calls.call_model(
                messages=[
                    {"role": "system", "content": build_reasoning_system_prompt()},
                    {"role": "user", "content": user_prompt},
                ],
                model=optimizer.model,
                model_parameters=optimizer.model_parameters,
                metadata=metadata_for_call,
                optimization_id=optimization_id,
            )
            logger.debug(f"Raw response from reasoning model: {content}")

            # Robust JSON Parsing and Validation
            json_result = None
            try:
                # Try direct JSON parsing
                json_result = json.loads(content)
            except json.JSONDecodeError:
                import re

                json_match = re.search(r"\{.*\}", content, re.DOTALL)
                if json_match:
                    try:
                        json_result = json.loads(json_match.group())
                    except json.JSONDecodeError as e:
                        raise ValueError(
                            f"Could not parse JSON extracted via regex: {e} - received: {json_match.group()}"
                        )
                else:
                    raise ValueError(
                        f"No JSON object found in response via regex. - received: {content}"
                    )

            # Validate the parsed JSON structure
            if isinstance(json_result, list) and len(json_result) == 1:
                json_result = json_result[0]

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
            json_result = sanitize_generated_prompts(json_result, metric.__name__)

            # Extract and log valid prompts
            valid_prompts: list[chat_prompt.ChatPrompt] = []
            for item in json_result["prompts"]:
                if (
                    isinstance(item, dict)
                    and "prompt" in item
                    and isinstance(item["prompt"], list)
                ):
                    # NOTE: might be brittle
                    if current_prompt.user:
                        user_text = current_prompt.user
                    else:
                        if current_prompt.messages is not None:
                            user_text = current_prompt.messages[-1]["content"]
                        else:
                            raise Exception("User content not found in chat-prompt!")

                    valid_prompts.append(
                        chat_prompt.ChatPrompt(
                            system=item["prompt"][0]["content"],
                            user=user_text,
                            tools=current_prompt.tools,
                            function_map=current_prompt.function_map,
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


def generate_mcp_candidate_prompts(
    optimizer: Any,
    current_prompt: chat_prompt.ChatPrompt,
    best_score: float,
    round_num: int,
    previous_rounds: list[OptimizationRound],
    metric: Callable,
    tool_segment_id: str,
    tool_name: str,
    build_history_context_fn: Callable,
    optimization_id: str | None = None,
    project_name: str | None = None,
    panel_style: str = "bright_magenta",
) -> list[chat_prompt.ChatPrompt]:
    """
    Generate MCP tool description candidate prompts.

    Args:
        optimizer: Reference to the optimizer instance
        current_prompt: Current best prompt
        best_score: Current best score
        round_num: Current round number
        previous_rounds: List of previous optimization rounds
        metric: Metric function
        tool_segment_id: ID of the tool segment to optimize
        tool_name: Name of the tool
        build_history_context_fn: Function to build history context
        optimization_id: Optional optimization ID
        project_name: Optional project name
        panel_style: Display panel style

    Returns:
        List of candidate prompts with updated tool descriptions
    """
    segments = {
        segment.segment_id: segment
        for segment in extract_prompt_segments(current_prompt)
    }
    if tool_segment_id not in segments:
        raise ValueError(f"Tool segment '{tool_segment_id}' not found in prompt")

    target_segment = segments[tool_segment_id]
    current_description = target_segment.content
    tool_metadata = target_segment.metadata.get("raw_tool", {})

    history_context = build_history_context_fn(previous_rounds)

    instruction = build_mcp_tool_description_user_prompt(
        tool_name=tool_name,
        current_description=current_description,
        tool_metadata_json=json.dumps(tool_metadata, indent=2),
        best_score=best_score,
        history_context=history_context,
        prompts_per_round=optimizer.prompts_per_round,
    )

    with reporting.display_candidate_generation_report(
        optimizer.prompts_per_round, verbose=optimizer.verbose
    ) as candidate_generation_report:
        try:
            # Prepare metadata for optimization algorithm call
            metadata_for_call_tools: dict[str, Any] = {}
            if project_name:
                metadata_for_call_tools["project_name"] = project_name
                metadata_for_call_tools["opik"] = {"project_name": project_name}
            if optimization_id and "opik" in metadata_for_call_tools:
                metadata_for_call_tools["opik"]["optimization_id"] = optimization_id
            metadata_for_call_tools["optimizer_name"] = optimizer.__class__.__name__
            metadata_for_call_tools["opik_call_type"] = "optimization_algorithm"

            content = _llm_calls.call_model(
                messages=[
                    {"role": "system", "content": build_reasoning_system_prompt()},
                    {"role": "user", "content": instruction},
                ],
                model=optimizer.model,
                model_parameters=optimizer.model_parameters,
                metadata=metadata_for_call_tools,
                optimization_id=optimization_id,
            )

            try:
                json_result = json.loads(content)
            except json.JSONDecodeError:
                import re

                json_match = re.search(r"\{.*\}", content, re.DOTALL)
                if not json_match:
                    raise ValueError("No JSON object found in reasoning output")
                json_result = json.loads(json_match.group())

            prompts_payload = json_result.get("prompts")
            if not isinstance(prompts_payload, list):
                raise ValueError("Reasoning output missing 'prompts' list")

            candidate_generation_report.set_generated_prompts()

            candidates: list[chat_prompt.ChatPrompt] = []
            for item in prompts_payload:
                if not isinstance(item, dict):
                    continue
                description = item.get("tool_description")
                if not isinstance(description, str) or not description.strip():
                    continue

                updated_prompt = apply_segment_updates(
                    current_prompt,
                    {tool_segment_id: description.strip()},
                )
                _sync_tool_description_in_system(updated_prompt)
                if (
                    description.strip()
                    and description.strip() != current_description.strip()
                ):
                    reporting.display_tool_description(
                        description.strip(),
                        f"Round {round_num + 1} tool description",
                        panel_style,
                    )
                candidates.append(updated_prompt)

            if not candidates:
                raise ValueError(
                    "Reasoning output did not produce valid tool descriptions"
                )

            return candidates
        except Exception as exc:
            raise ValueError(f"Error generating MCP prompt candidates: {exc}")
