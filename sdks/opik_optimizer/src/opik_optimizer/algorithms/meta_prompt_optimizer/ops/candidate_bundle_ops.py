"""
Agent-bundle candidate generation for the Meta-Prompt Optimizer.
"""

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
from .. import prompts as meta_prompts
from .. import reporting
from ..types import AgentBundleCandidate, AgentBundleCandidatesResponse, AgentMetadata

logger = logging.getLogger(__name__)


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


def generate_agent_bundle_candidates(
    optimizer: Any,
    current_prompts: dict[str, chat_prompt.ChatPrompt],
    best_score: float,
    round_num: int,
    previous_rounds: Sequence[OptimizationRound],
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
        logger.debug("\nGenerating agent bundle prompts for round %s", round_num + 1)
        logger.debug("Generating from agents: %s", list(current_prompts.keys()))
        logger.debug("Current best score: %.4f", best_score)

        pattern_guidance = ""
        if winning_patterns and random.random() < optimizer.pattern_injection_rate:
            pattern_guidance = "WINNING PATTERNS TO CONSIDER:\n"
            pattern_guidance += (
                "The following patterns have been successful in high-scoring prompts:\n"
            )
            for i, pattern in enumerate(winning_patterns, 1):
                pattern_guidance += f"{i}. {pattern}\n"
            pattern_guidance += "\nAdapt these patterns per agent where appropriate."
            logger.info("Injecting %s patterns into generation", len(winning_patterns))

        history_context = build_history_context_fn(previous_rounds)
        task_context_str = ""
        analysis_instruction = ""
        metric_focus_instruction = ""

        if optimizer.enable_context:
            task_context_str, _ = get_task_context_fn(metric=metric)
            analysis_instruction = (
                "Analyze the examples/feedback (if any), metric description, and score history."
            )
            metric_focus_instruction = (
                "Focus on improving evaluation scores while keeping each agent's role distinct."
            )
        else:
            analysis_instruction = (
                "Analyze score history and each agent's role before proposing changes."
            )
            metric_focus_instruction = "Generate effective, role-appropriate updates."

        agent_blocks = _format_agent_prompts_for_prompt(current_prompts)

        candidate_gen_template = optimizer.get_prompt("candidate_generation")
        reasoning_template = optimizer.get_prompt("reasoning_system")

        user_prompt = meta_prompts.build_candidate_generation_user_prompt(
            template=candidate_gen_template,
            current_prompt_messages="",
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

        except Exception as exc:
            if isinstance(exc, (BadRequestError, StructuredOutputParsingError)):
                raise
            raise ValueError(
                f"Unexpected error during agent bundle prompt generation: {exc}"
            )
