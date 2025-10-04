from typing import Any, TYPE_CHECKING, Union, List, Dict

import logging
import random
import json

from deap import creator as _creator

from . import prompts as evo_prompts
from . import reporting
from .. import utils


logger = logging.getLogger(__name__)
creator = _creator  # backward compt.


# Type alias for multimodal content
MessageContent = Union[str, List[Dict[str, Any]]]


def extract_text_from_content(content: MessageContent) -> str:
    """
    Extract text from message content, handling both string and structured formats.

    For structured content (multimodal), extracts only text parts and ignores images.

    Args:
        content: Message content (string or structured list)

    Returns:
        Extracted text as a string
    """
    if isinstance(content, str):
        return content

    if isinstance(content, list):
        # Extract text from structured content
        text_parts = []
        for part in content:
            if isinstance(part, dict) and part.get("type") == "text":
                text_parts.append(part.get("text", ""))

        return " ".join(text_parts)

    return str(content)


def rebuild_content_with_mutated_text(
    original_content: MessageContent,
    mutated_text: str,
) -> MessageContent:
    """
    Rebuild message content with mutated text while preserving images.

    If original content is structured (multimodal), replaces text parts with
    mutated text while keeping image parts intact.

    Args:
        original_content: Original message content
        mutated_text: The mutated text to use

    Returns:
        Rebuilt content with mutated text
    """
    if isinstance(original_content, str):
        # Simple case: return mutated text
        return mutated_text

    if isinstance(original_content, list):
        # Structured content: rebuild with mutated text + preserved images
        result_parts = []

        # First, add mutated text
        result_parts.append({
            "type": "text",
            "text": mutated_text,
        })

        # Then, preserve all image parts from original
        for part in original_content:
            if isinstance(part, dict) and part.get("type") == "image_url":
                result_parts.append(part)

        return result_parts

    # Fallback: return as string
    return mutated_text


class CrossoverOps:
    if TYPE_CHECKING:
        verbose: int
        output_style_guidance: str
        _call_model: Any

    def _deap_crossover_chunking_strategy(
        self, messages_1_str: str, messages_2_str: str
    ) -> tuple[str, str]:
        chunks1 = [
            chunk.strip() for chunk in messages_1_str.split(".") if chunk.strip()
        ]
        chunks2 = [
            chunk.strip() for chunk in messages_2_str.split(".") if chunk.strip()
        ]

        if len(chunks1) >= 2 and len(chunks2) >= 2:
            min_num_chunks = min(len(chunks1), len(chunks2))
            point = random.randint(1, min_num_chunks - 1)
            child1_chunks = chunks1[:point] + chunks2[point:]
            child2_chunks = chunks2[:point] + chunks1[point:]
            child1_str = ". ".join(child1_chunks) + ("." if child1_chunks else "")
            child2_str = ". ".join(child2_chunks) + ("." if child2_chunks else "")
            return child1_str, child2_str
        else:
            raise ValueError(
                "Not enough chunks in either prompt for chunk-level crossover"
            )

    def _deap_crossover_word_level(
        self, messages_1_str: str, messages_2_str: str
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

    def _deap_crossover(self, ind1: Any, ind2: Any) -> tuple[Any, Any]:
        """Crossover operation that preserves semantic meaning.
        Attempts chunk-level crossover first, then falls back to word-level.
        Now supports multimodal content by preserving images during crossover.
        """
        reporting.display_message(
            "      Recombining prompts by mixing and matching words and sentences.",
            verbose=self.verbose,
        )
        messages_1_orig: list[dict[str, MessageContent]] = ind1
        messages_2_orig: list[dict[str, MessageContent]] = ind2

        for i, message_1 in enumerate(messages_1_orig):
            role: str = message_1["role"]
            content_1 = message_1["content"]

            if (len(messages_2_orig) >= i + 1) and (messages_2_orig[i]["role"] == role):
                message_2 = messages_2_orig[i]
                content_2 = message_2["content"]

                # Extract text for crossover (handles both string and structured content)
                message_1_str = extract_text_from_content(content_1)
                message_2_str = extract_text_from_content(content_2)

                try:
                    child1_str, child2_str = self._deap_crossover_chunking_strategy(
                        message_1_str, message_2_str
                    )
                except ValueError:
                    child1_str, child2_str = self._deap_crossover_word_level(
                        message_1_str, message_2_str
                    )

                # Rebuild content with crossed-over text, preserving images
                messages_1_orig[i]["content"] = rebuild_content_with_mutated_text(content_1, child1_str)
                messages_2_orig[i]["content"] = rebuild_content_with_mutated_text(content_2, child2_str)
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

    def _llm_deap_crossover(self, ind1: Any, ind2: Any) -> tuple[Any, Any]:
        """Perform crossover by asking an LLM to blend two parent prompts."""
        reporting.display_message(
            "      Recombining prompts using an LLM.", verbose=self.verbose
        )

        parent1_messages: list[dict[str, MessageContent]] = ind1
        parent2_messages: list[dict[str, MessageContent]] = ind2
        current_output_style_guidance = self.output_style_guidance
        # Detect if we're working with multimodal prompts (check both parents)
        is_multimodal = evo_prompts._is_multimodal_prompt(parent1_messages) or evo_prompts._is_multimodal_prompt(parent2_messages)

        user_prompt_for_llm_crossover = evo_prompts.llm_crossover_user_prompt(
            parent1_messages, parent2_messages, current_output_style_guidance
        )
        try:
            logger.debug(
                f"Attempting LLM-driven crossover between: '{parent1_messages[:50]}...' and '{parent2_messages[:50]}...' aiming for style: '{current_output_style_guidance[:30]}...'"
            )
            response_content = self._call_model(
                messages=[
                    {
                        "role": "system",
                        "content": evo_prompts.llm_crossover_system_prompt(
                            current_output_style_guidance, is_multimodal=is_multimodal
                        ),
                    },
                    {"role": "user", "content": user_prompt_for_llm_crossover},
                ],
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
                extracted = self._extract_json_arrays(response_content)
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
            return self._deap_crossover(ind1, ind2)

    def _extract_json_arrays(self, text: str) -> list[str]:
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
