from typing import Any, Protocol

import copy
import logging
import random
import sys
import types

from deap import creator as _creator

from opik_optimizer.utils.display import display_message
from ..types import CrossoverResponse
from ....core import llm_calls as _llm_calls
from ....core.llm_calls import StructuredOutputParsingError
from ....api_objects.types import (
    Content,
    extract_text_from_content,
    rebuild_content_with_new_text,
)
from ....utils.prompt_library import PromptLibrary
from . import tool_ops


logger = logging.getLogger(__name__)
creator = _creator  # backward compt.

_reporting_module: Any = types.ModuleType(__name__ + ".reporting")
_reporting_module.display_message = display_message
sys.modules[_reporting_module.__name__] = _reporting_module
reporting = _reporting_module


class RandomLike(Protocol):
    def randint(self, a: int, b: int) -> int: ...

    def shuffle(self, seq: list[Any]) -> None: ...


def _deap_crossover_chunking_strategy(
    messages_1_str: str, messages_2_str: str, rng: RandomLike
) -> tuple[str, str]:
    chunks1 = [chunk.strip() for chunk in messages_1_str.split(".") if chunk.strip()]
    chunks2 = [chunk.strip() for chunk in messages_2_str.split(".") if chunk.strip()]

    if len(chunks1) >= 2 and len(chunks2) >= 2:
        min_num_chunks = min(len(chunks1), len(chunks2))
        point = rng.randint(1, min_num_chunks - 1)
        child1_chunks = chunks1[:point] + chunks2[point:]
        child2_chunks = chunks2[:point] + chunks1[point:]
        child1_str = ". ".join(child1_chunks) + ("." if child1_chunks else "")
        child2_str = ". ".join(child2_chunks) + ("." if child2_chunks else "")
        return child1_str, child2_str
    else:
        raise ValueError("Not enough chunks in either prompt for chunk-level crossover")


def _deap_crossover_word_level(
    messages_1_str: str, messages_2_str: str, rng: RandomLike
) -> tuple[str, str]:
    words1 = messages_1_str.split()
    words2 = messages_2_str.split()
    if not words1 or not words2:
        return messages_1_str, messages_2_str
    min_word_len = min(len(words1), len(words2))
    if min_word_len < 2:
        return messages_1_str, messages_2_str
    point = rng.randint(1, min_word_len - 1)
    child1_words = words1[:point] + words2[point:]
    child2_words = words2[:point] + words1[point:]
    return " ".join(child1_words), " ".join(child2_words)


def _crossover_messages(
    messages_1: list[dict[str, Any]],
    messages_2: list[dict[str, Any]],
    rng: RandomLike,
) -> tuple[list[dict[str, Any]], list[dict[str, Any]]]:
    """Apply crossover to a single prompt's messages.

    Handles both string content and content parts (preserving images/video).
    """
    messages_1_result = copy.deepcopy(messages_1)
    messages_2_result = copy.deepcopy(messages_2)

    for i, message_1 in enumerate(messages_1_result):
        role: str = message_1["role"]
        content_1: Content = message_1["content"]
        if (len(messages_2_result) >= i + 1) and (messages_2_result[i]["role"] == role):
            message_2 = messages_2_result[i]
            content_2: Content = message_2["content"]

            # Extract text from content (handles both string and content parts)
            text_1 = extract_text_from_content(content_1)
            text_2 = extract_text_from_content(content_2)

            try:
                child1_text, child2_text = _deap_crossover_chunking_strategy(
                    text_1, text_2, rng
                )
            except ValueError:
                child1_text, child2_text = _deap_crossover_word_level(
                    text_1, text_2, rng
                )

            # Rebuild content preserving non-text parts (images/video)
            messages_1_result[i]["content"] = rebuild_content_with_new_text(
                content_1, child1_text
            )
            messages_2_result[i]["content"] = rebuild_content_with_new_text(
                content_2, child2_text
            )

    return messages_1_result, messages_2_result


def deap_crossover(
    ind1: Any,
    ind2: Any,
    *,
    optimizer: Any | None = None,
    verbose: int = 1,
    rng: random.Random | None = None,
) -> tuple[Any, Any]:
    """Crossover operation that preserves semantic meaning.

    Operates on dict-based individuals (prompt_name -> messages).
    Applies crossover to ALL prompts in the dict.
    Handles both string content and content parts (preserving images/video).
    """
    reporting.display_message(
        "      Recombining prompts by mixing and matching words and sentences.",
        verbose=verbose,
    )

    rng = rng or random.Random()

    # Individuals are dicts mapping prompt_name -> messages
    child1_data: dict[str, list[dict[str, Any]]] = {}
    child2_data: dict[str, list[dict[str, Any]]] = {}

    # Apply crossover to each prompt in the dict
    for prompt_name in ind1.keys():
        if prompt_name in ind2:
            messages_1 = ind1[prompt_name]
            messages_2 = ind2[prompt_name]
            child1_messages, child2_messages = _crossover_messages(
                messages_1, messages_2, rng
            )
            child1_data[prompt_name] = child1_messages
            child2_data[prompt_name] = child2_messages
        else:
            # Prompt only in ind1 - keep as is in both children
            child1_data[prompt_name] = copy.deepcopy(ind1[prompt_name])
            child2_data[prompt_name] = copy.deepcopy(ind1[prompt_name])

    # Handle prompts only in ind2
    for prompt_name in ind2.keys():
        if prompt_name not in ind1:
            child1_data[prompt_name] = copy.deepcopy(ind2[prompt_name])
            child2_data[prompt_name] = copy.deepcopy(ind2[prompt_name])

    child1 = creator.Individual(child1_data)  # type: ignore[attr-defined]
    child2 = creator.Individual(child2_data)  # type: ignore[attr-defined]

    # Preserve prompts_metadata from parents (merge, preferring ind1)
    metadata_1 = getattr(ind1, "prompts_metadata", {})
    metadata_2 = getattr(ind2, "prompts_metadata", {})
    merged_metadata = {**metadata_2, **metadata_1}
    child1_metadata = copy.deepcopy(merged_metadata)
    child2_metadata = copy.deepcopy(merged_metadata)

    # Apply tool updates if optimizing tools
    optimize_tools = bool(getattr(optimizer, "_optimize_tools", False))
    tool_names = getattr(optimizer, "_tool_names", None)
    metric = getattr(optimizer, "_evaluation_metric", None)
    if optimize_tools and optimizer is not None:
        child1_metadata = tool_ops.apply_tool_updates_to_metadata(
            optimizer=optimizer,
            child_data=child1_data,
            metadata=child1_metadata,
            tool_names=tool_names,
            metric=metric,
        )
        child2_metadata = tool_ops.apply_tool_updates_to_metadata(
            optimizer=optimizer,
            child_data=child2_data,
            metadata=child2_metadata,
            tool_names=tool_names,
            metric=metric,
        )

    setattr(child1, "prompts_metadata", child1_metadata)
    setattr(child2, "prompts_metadata", child2_metadata)

    return child1, child2


def _call_llm_for_crossover(
    messages_1: list[dict[str, Any]],
    messages_2: list[dict[str, Any]],
    output_style_guidance: str,
    model: str,
    model_parameters: dict[str, Any],
    prompts: PromptLibrary,
    system_template_key: str,
    user_template_key: str,
) -> tuple[list[dict[str, Any]], list[dict[str, Any]]]:
    """Call the LLM for crossover and return the laddered child prompts."""
    user_prompt = prompts.get(
        user_template_key,
        parent1_messages=messages_1,
        parent2_messages=messages_2,
        style=output_style_guidance,
    )
    response = _llm_calls.call_model(
        messages=[
            {
                "role": "system",
                "content": prompts.get(
                    system_template_key,
                    style=output_style_guidance,
                ),
            },
            {"role": "user", "content": user_prompt},
        ],
        model=model,
        model_parameters=model_parameters,
        response_model=CrossoverResponse,
        is_reasoning=True,
        return_all=_llm_calls.requested_multiple_candidates(model_parameters),
    )

    response_item = response[0] if isinstance(response, list) else response
    first_child_messages = [msg.model_dump() for msg in response_item.child_1]
    second_child_messages = [msg.model_dump() for msg in response_item.child_2]
    return first_child_messages, second_child_messages


def _llm_crossover_messages(
    messages_1: list[dict[str, Any]],
    messages_2: list[dict[str, Any]],
    output_style_guidance: str,
    model: str,
    model_parameters: dict[str, Any],
    prompts: PromptLibrary,
) -> tuple[list[dict[str, Any]], list[dict[str, Any]]]:
    """Apply LLM-based crossover to a single prompt's messages."""
    return _call_llm_for_crossover(
        messages_1=messages_1,
        messages_2=messages_2,
        output_style_guidance=output_style_guidance,
        model=model,
        model_parameters=model_parameters,
        prompts=prompts,
        system_template_key="llm_crossover_system_prompt_template",
        user_template_key="llm_crossover_user_prompt_template",
    )


def _semantic_crossover_messages(
    messages_1: list[dict[str, Any]],
    messages_2: list[dict[str, Any]],
    output_style_guidance: str,
    model: str,
    model_parameters: dict[str, Any],
    prompts: PromptLibrary,
) -> tuple[list[dict[str, Any]], list[dict[str, Any]]]:
    """Apply semantic LLM-based crossover to a single prompt's messages."""
    return _call_llm_for_crossover(
        messages_1=messages_1,
        messages_2=messages_2,
        output_style_guidance=output_style_guidance,
        model=model,
        model_parameters=model_parameters,
        prompts=prompts,
        system_template_key="semantic_crossover_system_prompt_template",
        user_template_key="semantic_crossover_user_prompt_template",
    )


def llm_deap_crossover(
    ind1: Any,
    ind2: Any,
    output_style_guidance: str,
    model: str,
    model_parameters: dict[str, Any],
    prompts: PromptLibrary,
    use_semantic: bool = False,
    verbose: int = 1,
    rng: random.Random | None = None,
    optimizer: Any | None = None,
) -> tuple[Any, Any]:
    """Perform crossover by asking an LLM to blend two parent prompts.

    Operates on dict-based individuals (prompt_name -> messages).
    Applies LLM crossover to ALL prompts in the dict.
    Falls back to deap_crossover on failure.
    """
    reporting.display_message(
        "      Recombining prompts using an LLM.", verbose=verbose
    )

    # Individuals are dicts mapping prompt_name -> messages
    child1_data: dict[str, list[dict[str, Any]]] = {}
    child2_data: dict[str, list[dict[str, Any]]] = {}

    rng = rng or random.Random()

    try:
        # Apply LLM crossover to each prompt in the dict
        for prompt_name in ind1.keys():
            if prompt_name in ind2:
                messages_1 = ind1[prompt_name]
                messages_2 = ind2[prompt_name]

                logger.debug(
                    f"Attempting LLM-driven crossover for prompt '{prompt_name}'"
                )

                try:
                    if use_semantic:
                        try:
                            child1_messages, child2_messages = (
                                _semantic_crossover_messages(
                                    messages_1,
                                    messages_2,
                                    output_style_guidance,
                                    model,
                                    model_parameters,
                                    prompts=prompts,
                                )
                            )
                        except (StructuredOutputParsingError, Exception) as e:
                            logger.debug(
                                "Semantic crossover failed for prompt '%s': %s",
                                prompt_name,
                                e,
                            )
                            child1_messages, child2_messages = _llm_crossover_messages(
                                messages_1,
                                messages_2,
                                output_style_guidance,
                                model,
                                model_parameters,
                                prompts=prompts,
                            )
                    else:
                        child1_messages, child2_messages = _llm_crossover_messages(
                            messages_1,
                            messages_2,
                            output_style_guidance,
                            model,
                            model_parameters,
                            prompts=prompts,
                        )
                    child1_data[prompt_name] = child1_messages
                    child2_data[prompt_name] = child2_messages
                except (StructuredOutputParsingError, Exception) as e:
                    logger.warning(
                        f"LLM crossover failed for prompt '{prompt_name}': {e}. Using DEAP crossover."
                    )
                    child1_messages, child2_messages = _crossover_messages(
                        messages_1, messages_2, rng
                    )
                    child1_data[prompt_name] = child1_messages
                    child2_data[prompt_name] = child2_messages
            else:
                # Prompt only in ind1 - keep as is in both children
                child1_data[prompt_name] = copy.deepcopy(ind1[prompt_name])
                child2_data[prompt_name] = copy.deepcopy(ind1[prompt_name])

        # Handle prompts only in ind2
        for prompt_name in ind2.keys():
            if prompt_name not in ind1:
                child1_data[prompt_name] = copy.deepcopy(ind2[prompt_name])
                child2_data[prompt_name] = copy.deepcopy(ind2[prompt_name])

        child1 = creator.Individual(child1_data)  # type: ignore[attr-defined]
        child2 = creator.Individual(child2_data)  # type: ignore[attr-defined]

        # Preserve prompts_metadata from parents (merge, preferring ind1)
        metadata_1 = getattr(ind1, "prompts_metadata", {})
        metadata_2 = getattr(ind2, "prompts_metadata", {})
        merged_metadata = {**metadata_2, **metadata_1}

        # Apply tool updates if optimizing tools
        optimize_tools = bool(getattr(optimizer, "_optimize_tools", False))
        tool_names = getattr(optimizer, "_tool_names", None)
        metric = getattr(optimizer, "_evaluation_metric", None)
        if optimize_tools and optimizer is not None:
            merged_metadata = tool_ops.apply_tool_updates_to_metadata(
                optimizer=optimizer,
                child_data=child1_data,
                metadata=merged_metadata,
                tool_names=tool_names,
                metric=metric,
            )
        setattr(child1, "prompts_metadata", copy.deepcopy(merged_metadata))
        setattr(child2, "prompts_metadata", copy.deepcopy(merged_metadata))

        return child1, child2

    except Exception as e:
        logger.warning(
            f"LLM-driven crossover failed: {e}. Falling back to DEAP crossover."
        )
        return deap_crossover(ind1, ind2, optimizer=optimizer, verbose=verbose)
