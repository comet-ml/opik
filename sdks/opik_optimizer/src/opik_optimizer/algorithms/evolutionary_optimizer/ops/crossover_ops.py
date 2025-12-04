from typing import Any

import logging
import random
import json

from deap import creator as _creator

from .. import prompts as evo_prompts
from .. import reporting
from .... import utils, _llm_calls


logger = logging.getLogger(__name__)
creator = _creator  # backward compt.


def _extract_json_arrays(text: str) -> list[str]:
    """Extract top-level JSON array substrings from arbitrary text.
    This helps when models return multiple arrays like `[...],\n[...]`.
    """
    arrays: list[str] = []
    depth = 0
    start: int | None = None
    in_str = False
    escape = False
    for i, ch in enumerate(text):
        if escape:
            # current char is escaped; skip special handling
            escape = False
            continue
        if ch == "\\":
            escape = True
            continue
        if ch == '"':
            in_str = not in_str
            continue
        if in_str:
            continue
        if ch == "[":
            if depth == 0:
                start = i
            depth += 1
        elif ch == "]" and depth > 0:
            depth -= 1
            if depth == 0 and start is not None:
                arrays.append(text[start : i + 1])
                start = None
    return arrays


def _deap_crossover_chunking_strategy(
    messages_1_str: str, messages_2_str: str
) -> tuple[str, str]:
    chunks1 = [chunk.strip() for chunk in messages_1_str.split(".") if chunk.strip()]
    chunks2 = [chunk.strip() for chunk in messages_2_str.split(".") if chunk.strip()]

    if len(chunks1) >= 2 and len(chunks2) >= 2:
        min_num_chunks = min(len(chunks1), len(chunks2))
        point = random.randint(1, min_num_chunks - 1)
        child1_chunks = chunks1[:point] + chunks2[point:]
        child2_chunks = chunks2[:point] + chunks1[point:]
        child1_str = ". ".join(child1_chunks) + ("." if child1_chunks else "")
        child2_str = ". ".join(child2_chunks) + ("." if child2_chunks else "")
        return child1_str, child2_str
    else:
        raise ValueError("Not enough chunks in either prompt for chunk-level crossover")


def _deap_crossover_word_level(
    messages_1_str: str, messages_2_str: str
) -> tuple[str, str]:
    words1 = messages_1_str.split()
    words2 = messages_2_str.split()
    if not words1 or not words2:
        return messages_1_str, messages_2_str
    min_word_len = min(len(words1), len(words2))
    if min_word_len < 2:
        return messages_1_str, messages_2_str
    point = random.randint(1, min_word_len - 1)
    child1_words = words1[:point] + words2[point:]
    child2_words = words2[:point] + words1[point:]
    return " ".join(child1_words), " ".join(child2_words)


def deap_crossover(ind1: Any, ind2: Any, verbose: int = 1) -> tuple[Any, Any]:
    """Crossover operation that preserves semantic meaning.
    Attempts chunk-level crossover first, then falls back to word-level.
    """
    reporting.display_message(
        "      Recombining prompts by mixing and matching words and sentences.",
        verbose=verbose,
    )
    messages_1_orig: list[dict[str, str]] = ind1
    messages_2_orig: list[dict[str, str]] = ind2

    for i, message_1 in enumerate(messages_1_orig):
        role: str = message_1["role"]
        message_1_str: str = message_1["content"]
        if (len(messages_2_orig) >= i + 1) and (messages_2_orig[i]["role"] == role):
            message_2 = messages_2_orig[i]
            message_2_str: str = message_2["content"]
            try:
                child1_str, child2_str = _deap_crossover_chunking_strategy(
                    message_1_str, message_2_str
                )
            except ValueError:
                child1_str, child2_str = _deap_crossover_word_level(
                    message_1_str, message_2_str
                )
            messages_1_orig[i]["content"] = child1_str
            messages_2_orig[i]["content"] = child2_str
        else:
            pass

    child1 = creator.Individual(messages_1_orig)
    child2 = creator.Individual(messages_2_orig)

    # Preserve tools and function_map from parents
    if hasattr(ind1, "tools"):
        setattr(child1, "tools", getattr(ind1, "tools"))
    if hasattr(ind1, "function_map"):
        setattr(child1, "function_map", getattr(ind1, "function_map"))
    if hasattr(ind2, "tools"):
        setattr(child2, "tools", getattr(ind2, "tools"))
    if hasattr(ind2, "function_map"):
        setattr(child2, "function_map", getattr(ind2, "function_map"))

    return child1, child2


def _semantic_crossover(
    parent1_messages: list[dict[str, str]],
    parent2_messages: list[dict[str, str]],
    output_style_guidance: str,
    model: str,
    model_parameters: dict[str, Any],
) -> tuple[list[dict[str, str]], list[dict[str, str]]]:
    """
    Semantic crossover: Extract key instructions from both parents
    and create meaningful combinations (inspired by GEPA's instruction blending).

    This is more sophisticated than word/sentence swapping - it understands
    and recombines the semantic content.
    """
    system_prompt = f"""You are an expert at analyzing and combining prompts.

Your task is to create two new prompts by meaningfully blending elements from two parent prompts.

Process:
1. Analyze each parent prompt to identify:
   - Core instructions and objectives
   - Key constraints or requirements
   - Structural approaches
   - Phrasing styles

2. Create two distinct offspring by:
   - Combining complementary elements from both parents
   - Preserving semantic coherence (don't just swap sentences)
   - Maintaining {output_style_guidance}
   - Creating meaningful variations (not copies)

3. Ensure offspring are:
   - Grammatically correct
   - Logically structured
   - Different from each parent
   - Different from each other

Return ONLY a JSON array with two prompt arrays:
[
  [
    {{"role": "system", "content": "first offspring content"}},
    {{"role": "user", "content": "..."}}
  ],
  [
    {{"role": "system", "content": "second offspring content"}},
    {{"role": "user", "content": "..."}}
  ]
]"""

    user_prompt = f"""Parent Prompt 1:
{json.dumps(parent1_messages, indent=2)}

Parent Prompt 2:
{json.dumps(parent2_messages, indent=2)}

Create two new prompts by semantically blending these parents.
Focus on combining their strengths and creating meaningful variations."""

    try:
        response_content = _llm_calls.call_model(
            messages=[
                {"role": "system", "content": system_prompt},
                {"role": "user", "content": user_prompt},
            ],
            model=model,
            model_parameters=model_parameters,
            is_reasoning=True,
        )

        # Parse response
        children: list[list[dict[str, str]]] = []
        try:
            parsed = json.loads(response_content)
            if isinstance(parsed, list) and len(parsed) >= 2:
                children = parsed[:2]
        except Exception:
            # Fallback: try to extract arrays
            extracted = _extract_json_arrays(response_content)
            for arr in extracted[:2]:
                try:
                    parsed = json.loads(arr)
                    if isinstance(parsed, list):
                        children.append(parsed)
                except Exception:
                    continue

        if len(children) >= 2:
            return children[0], children[1]
        else:
            raise ValueError(
                "Could not extract two valid children from semantic crossover"
            )

    except Exception as e:
        logger.warning(f"Semantic crossover failed: {e}")
        raise


def llm_deap_crossover(
    ind1: Any,
    ind2: Any,
    output_style_guidance: str,
    model: str,
    model_parameters: dict[str, Any],
    verbose: int = 1,
    use_semantic: bool = True,
) -> tuple[Any, Any]:
    """Perform crossover by asking an LLM to blend two parent prompts."""
    reporting.display_message(
        "      Recombining prompts using an LLM.", verbose=verbose
    )

    parent1_messages: list[dict[str, str]] = ind1
    parent2_messages: list[dict[str, str]] = ind2
    current_output_style_guidance = output_style_guidance

    # Try semantic crossover first
    if use_semantic:
        try:
            logger.debug("Attempting semantic crossover (instruction blending)")
            child1_messages, child2_messages = _semantic_crossover(
                parent1_messages,
                parent2_messages,
                current_output_style_guidance,
                model,
                model_parameters,
            )

            # Create individuals
            child1 = creator.Individual(child1_messages)
            child2 = creator.Individual(child2_messages)

            # Preserve tools and function_map
            if hasattr(ind1, "tools"):
                setattr(child1, "tools", getattr(ind1, "tools"))
            if hasattr(ind1, "function_map"):
                setattr(child1, "function_map", getattr(ind1, "function_map"))
            if hasattr(ind2, "tools"):
                setattr(child2, "tools", getattr(ind2, "tools"))
            if hasattr(ind2, "function_map"):
                setattr(child2, "function_map", getattr(ind2, "function_map"))

            logger.debug("Semantic crossover successful")
            return child1, child2

        except Exception as e:
            logger.debug(f"Semantic crossover failed, falling back to standard: {e}")

    # Original LLM crossover logic as fallback
    user_prompt_for_llm_crossover = evo_prompts.llm_crossover_user_prompt(
        parent1_messages, parent2_messages, current_output_style_guidance
    )
    try:
        logger.debug(
            f"Attempting LLM-driven crossover between: '{parent1_messages[:50]}...' and '{parent2_messages[:50]}...' aiming for style: '{current_output_style_guidance[:30]}...'"
        )
        response_content = _llm_calls.call_model(
            messages=[
                {
                    "role": "system",
                    "content": evo_prompts.llm_crossover_system_prompt(
                        current_output_style_guidance
                    ),
                },
                {"role": "user", "content": user_prompt_for_llm_crossover},
            ],
            model=model,
            model_parameters=model_parameters,
            is_reasoning=True,
        )
        logger.debug(f"Raw LLM response for crossover: {response_content}")

        # First, try strict JSON parsing
        json_response = None
        try:
            json_response = utils.json_to_dict(response_content)
        except Exception:
            # Continue with heuristic extraction below
            json_response = None
        children: list[list[dict[str, str]]] = []
        if isinstance(json_response, list):
            children = [c for c in json_response if isinstance(c, list)]

        # If strict parse failed to yield children, try extracting arrays heuristically
        if not children:
            extracted = _extract_json_arrays(response_content)
            for arr in extracted:
                try:
                    parsed = json.loads(arr)
                    if isinstance(parsed, list) and all(
                        isinstance(m, dict) and {"role", "content"} <= set(m.keys())
                        for m in parsed
                    ):
                        children.append(parsed)
                except Exception:
                    continue

        if len(children) == 0:
            raise ValueError("LLM response did not include any valid child prompts")

        # We only need two children; if only one returned, duplicate pattern from DEAP
        first_child_messages = children[0]
        second_child_messages = children[1] if len(children) > 1 else children[0]

        child1 = creator.Individual(first_child_messages)
        child2 = creator.Individual(second_child_messages)

        # Preserve tools and function_map from parents
        if hasattr(ind1, "tools"):
            setattr(child1, "tools", getattr(ind1, "tools"))
        if hasattr(ind1, "function_map"):
            setattr(child1, "function_map", getattr(ind1, "function_map"))
        if hasattr(ind2, "tools"):
            setattr(child2, "tools", getattr(ind2, "tools"))
        if hasattr(ind2, "function_map"):
            setattr(child2, "function_map", getattr(ind2, "function_map"))

        return child1, child2
    except Exception as e:
        logger.warning(
            f"LLM-driven crossover failed: {e}. Falling back to DEAP crossover."
        )
        return deap_crossover(ind1, ind2)
