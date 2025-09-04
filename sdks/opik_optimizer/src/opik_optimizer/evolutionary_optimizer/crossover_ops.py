from typing import Any, Dict, List, Tuple

import logging
import random

from deap import creator as _creator

from . import prompts as evo_prompts
from . import reporting
from .. import utils


logger = logging.getLogger(__name__)
creator = _creator  # backward compt.


class CrossoverOps:
    def _deap_crossover_chunking_strategy(
        self, messages_1_str: str, messages_2_str: str
    ) -> Tuple[str, str]:
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
    ) -> Tuple[str, str]:
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

    def _deap_crossover(self, ind1: Any, ind2: Any) -> Tuple[Any, Any]:
        """Crossover operation that preserves semantic meaning.
        Attempts chunk-level crossover first, then falls back to word-level.
        """
        reporting.display_message(
            "      Recombining prompts by mixing and matching words and sentences.",
            verbose=self.verbose,
        )
        messages_1_orig: List[Dict[str, str]] = ind1
        messages_2_orig: List[Dict[str, str]] = ind2

        for i, message_1 in enumerate(messages_1_orig):
            role: str = message_1["role"]
            message_1_str: str = message_1["content"]
            if (len(messages_2_orig) >= i + 1) and (messages_2_orig[i]["role"] == role):
                message_2 = messages_2_orig[i]
                message_2_str: str = message_2["content"]
                try:
                    child1_str, child2_str = self._deap_crossover_chunking_strategy(
                        message_1_str, message_2_str
                    )
                except ValueError:
                    child1_str, child2_str = self._deap_crossover_word_level(
                        message_1_str, message_2_str
                    )
                messages_1_orig[i]["content"] = child1_str
                messages_2_orig[i]["content"] = child2_str
            else:
                pass

        return creator.Individual(messages_1_orig), creator.Individual(messages_2_orig)

    def _llm_deap_crossover(self, ind1: Any, ind2: Any) -> Tuple[Any, Any]:
        """Perform crossover by asking an LLM to blend two parent prompts."""
        reporting.display_message(
            "      Recombining prompts using an LLM.", verbose=self.verbose
        )

        parent1_messages: List[Dict[str, str]] = ind1
        parent2_messages: List[Dict[str, str]] = ind2
        current_output_style_guidance = self.output_style_guidance

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
                            current_output_style_guidance
                        ),
                    },
                    {"role": "user", "content": user_prompt_for_llm_crossover},
                ],
                is_reasoning=True,
            )
            logger.debug(f"Raw LLM response for crossover: {response_content}")

            json_response = utils.json_to_dict(response_content)
            if not isinstance(json_response, list) or len(json_response) < 1:
                raise ValueError("LLM response is not a list of prompts")

            children: List[List[Dict[str, str]]] = [
                c for c in json_response if isinstance(c, list)
            ]
            if len(children) == 0:
                raise ValueError("LLM response did not include any valid child prompts")

            # We only need two children; if only one returned, duplicate pattern from DEAP
            first_child = children[0]
            second_child = children[1] if len(children) > 1 else children[0]
            return creator.Individual(first_child), creator.Individual(second_child)
        except Exception as e:
            logger.warning(
                f"LLM-driven crossover failed: {e}. Falling back to original parents."
            )
            return ind1, ind2