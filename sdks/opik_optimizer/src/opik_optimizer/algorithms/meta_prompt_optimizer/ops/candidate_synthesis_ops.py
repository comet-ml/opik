"""
Synthesis candidate generation for the Meta-Prompt Optimizer.
"""

import ast
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
from ....core.results import OptimizationRound, round_payload
from ....utils import display as display_utils
from ....utils.prompt_roles import apply_role_constraints, count_disallowed_role_updates
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


def _parse_synthesis_json(content_item: str) -> dict[str, Any]:
    try:
        return json.loads(normalize_llm_text(content_item))
    except json.JSONDecodeError:
        json_match = re.search(r"\{.*\}", content_item, re.DOTALL)
        if not json_match:
            raise ValueError(
                f"No JSON object found in synthesis response: {content_item}"
            )
        try:
            return json.loads(normalize_llm_text(json_match.group()))
        except json.JSONDecodeError as exc:
            raise ValueError(
                f"Could not parse synthesis JSON: {exc} - received: {json_match.group()}"
            )


def _normalize_synthesis_json(json_result: Any) -> dict[str, Any]:
    if isinstance(json_result, list):
        if (
            len(json_result) == 1
            and isinstance(json_result[0], dict)
            and "prompts" in json_result[0]
        ):
            json_result = json_result[0]
        elif all(isinstance(item, dict) and "prompt" in item for item in json_result):
            json_result = {"prompts": json_result}
    if not isinstance(json_result, dict) or "prompts" not in json_result:
        raise ValueError(f"Invalid synthesis JSON structure - received: {json_result}")
    if not isinstance(json_result["prompts"], list):
        raise ValueError("'prompts' key does not contain a list in synthesis response")
    return json_result


def _parse_synthesis_prompt_items(contents: list[Any]) -> list[dict[str, Any]]:
    prompt_items: list[dict[str, Any]] = []
    for content_item in contents:
        if hasattr(content_item, "model_dump"):
            normalized = content_item.model_dump()
        else:
            normalized = content_item
        if isinstance(normalized, str):
            json_result = _normalize_synthesis_json(_parse_synthesis_json(normalized))
        elif isinstance(normalized, dict):
            json_result = _normalize_synthesis_json(normalized)
        else:
            raise ValueError(
                "Synthesis response must be a string or dict; received %s"
                % type(normalized).__name__
            )
        prompt_items.extend(json_result["prompts"])
    return prompt_items


def _build_synthesis_prompts(
    *,
    optimizer: Any,
    current_prompt: chat_prompt.ChatPrompt,
    round_num: int | None,
    prompt_items: list[dict[str, Any]],
) -> list[chat_prompt.ChatPrompt]:
    valid_prompts: list[chat_prompt.ChatPrompt] = []
    for idx, item in enumerate(prompt_items, start=1):
        if (
            isinstance(item, dict)
            and "prompt" in item
            and isinstance(item["prompt"], list)
        ):
            if current_prompt.user:
                user_text = current_prompt.user
            elif current_prompt.messages is not None:
                # Handle both string and list[ContentPart] content types
                content = current_prompt.messages[-1]["content"]
                if isinstance(content, list):
                    from ....api_objects.types import extract_text_from_content

                    user_text = extract_text_from_content(content)
                else:
                    user_text = content
            else:
                raise Exception("User content not found in chat-prompt!")

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
            allowed_roles = getattr(optimizer, "_optimizable_roles", None)
            if allowed_roles is not None:
                constrained = apply_role_constraints(
                    current_prompt.get_messages(),
                    valid_prompts[-1].get_messages(),
                    allowed_roles,
                )
                dropped = count_disallowed_role_updates(
                    current_prompt.get_messages(),
                    valid_prompts[-1].get_messages(),
                    allowed_roles,
                )
                if dropped:
                    logger.debug(
                        "MetaPrompt synthesis candidate %s dropped %s update(s) due to optimize_prompt constraints.",
                        idx,
                        dropped,
                    )
                valid_prompts[-1].set_messages(constrained)
            prompt_ref = valid_prompts[-1]
            optimizer._candidate_metadata_by_prompt_id[id(prompt_ref)] = {
                "improvement_focus": item.get("improvement_focus"),
                "reasoning": item.get("reasoning"),
            }
            reporting.log_candidate_generated(
                round_num=round_num,
                candidate_id=(
                    f"round{round_num}_cand{idx}" if round_num is not None else None
                ),
                prompt_messages=prompt_ref.get_messages(),
                improvement_focus=item.get("improvement_focus"),
                reasoning=item.get("reasoning"),
            )
        else:
            logger.warning("Skipping invalid synthesis prompt item structure: %s", item)
    return valid_prompts


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

            base_messages = [
                {"role": "system", "content": synthesis_system_prompt},
                {"role": "user", "content": synthesis_user_prompt},
            ]
            try:
                content = _llm_calls.call_model(
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
            except (BadRequestError, StructuredOutputParsingError):
                retry_messages = _with_strict_json_instruction(base_messages)
                content = _llm_calls.call_model(
                    messages=retry_messages,
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
