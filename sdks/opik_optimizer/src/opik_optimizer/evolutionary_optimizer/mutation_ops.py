from typing import Any, TYPE_CHECKING
from collections.abc import Callable

import json
import logging
import random

from . import prompts as evo_prompts
from .mcp import EvolutionaryMCPContext, tool_description_mutation
from ..optimization_config import chat_prompt
from .. import utils
from . import reporting


logger = logging.getLogger(__name__)


class MutationOps:
    if TYPE_CHECKING:
        _calculate_population_diversity: Any
        DEFAULT_DIVERSITY_THRESHOLD: float
        verbose: int
        output_style_guidance: str
        _get_task_description_for_llm: Any
        _call_model: Any
        _mcp_context: EvolutionaryMCPContext | None
        _update_individual_with_prompt: Callable[[Any, chat_prompt.ChatPrompt], Any]

    def _deap_mutation(
        self, individual: Any, initial_prompt: chat_prompt.ChatPrompt
    ) -> Any:
        """Enhanced mutation operation with multiple strategies."""
        prompt = chat_prompt.ChatPrompt(messages=individual)

        mcp_context = getattr(self, "_mcp_context", None)
        if mcp_context is not None:
            mutated_prompt = tool_description_mutation(self, prompt, mcp_context)
            if mutated_prompt is not None:
                reporting.display_success(
                    "      Mutation successful, tool description updated (MCP mutation).",
                    verbose=self.verbose,
                )
                self._update_individual_with_prompt(individual, mutated_prompt)
                return (individual,)

        # Choose mutation strategy based on current diversity
        diversity = self._calculate_population_diversity()

        # Determine thresholds based on diversity
        if diversity < self.DEFAULT_DIVERSITY_THRESHOLD:
            # Low diversity - use more aggressive mutations (higher chance for semantic)
            semantic_threshold = 0.5
            structural_threshold = 0.8  # semantic_threshold + 0.3
        else:
            # Good diversity - use more conservative mutations (higher chance for word_level)
            semantic_threshold = 0.4
            structural_threshold = 0.7  # semantic_threshold + 0.3

        mutation_choice = random.random()

        if mutation_choice > structural_threshold:
            mutated_prompt = self._word_level_mutation_prompt(prompt)
            reporting.display_success(
                "      Mutation successful, prompt has been edited by randomizing words (word-level mutation).",
                verbose=self.verbose,
            )
            self._update_individual_with_prompt(individual, mutated_prompt)
            return (individual,)
        elif mutation_choice > semantic_threshold:
            mutated_prompt = self._structural_mutation(prompt)
            reporting.display_success(
                "      Mutation successful, prompt has been edited by reordering, combining, or splitting sentences (structural mutation).",
                verbose=self.verbose,
            )
            self._update_individual_with_prompt(individual, mutated_prompt)
            return (individual,)
        else:
            mutated_prompt = self._semantic_mutation(prompt, initial_prompt)
            reporting.display_success(
                "      Mutation successful, prompt has been edited using an LLM (semantic mutation).",
                verbose=self.verbose,
            )
            self._update_individual_with_prompt(individual, mutated_prompt)
            return (individual,)

    def _semantic_mutation(
        self, prompt: chat_prompt.ChatPrompt, initial_prompt: chat_prompt.ChatPrompt
    ) -> chat_prompt.ChatPrompt:
        """Enhanced semantic mutation with multiple strategies."""
        current_output_style_guidance = self.output_style_guidance
        if random.random() < 0.1:
            return self._radical_innovation_mutation(prompt, initial_prompt)

        try:
            strategy = random.choice(
                [
                    "rephrase",
                    "simplify",
                    "elaborate",
                    "restructure",
                    "focus",
                    "increase_complexity_and_detail",
                ]
            )

            strategy_prompts = evo_prompts.mutation_strategy_prompts(
                current_output_style_guidance
            )
            user_prompt_for_semantic_mutation = (
                evo_prompts.semantic_mutation_user_prompt(
                    prompt.get_messages(),
                    self._get_task_description_for_llm(initial_prompt),
                    current_output_style_guidance,
                    strategy_prompts[strategy],
                )
            )
            response = self._call_model(
                messages=[
                    {
                        "role": "system",
                        "content": evo_prompts.semantic_mutation_system_prompt(
                            current_output_style_guidance
                        ),
                    },
                    {"role": "user", "content": user_prompt_for_semantic_mutation},
                ],
                is_reasoning=True,
            )

            try:
                messages = utils.json_to_dict(response.strip())
            except Exception as parse_exc:
                raise RuntimeError(
                    f"Error parsing semantic mutation response as JSON. "
                    f"Response: {response!r}\nOriginal error: {parse_exc}"
                ) from parse_exc
            return chat_prompt.ChatPrompt(messages=messages)
        except Exception as e:
            reporting.display_error(
                f"      Error in semantic mutation, this is usually a parsing error: {e}",
                verbose=self.verbose,
            )
            return prompt

    def _structural_mutation(
        self, prompt: chat_prompt.ChatPrompt
    ) -> chat_prompt.ChatPrompt:
        """Perform structural mutation (reordering, combining, splitting)."""
        mutated_messages: list[dict[str, str]] = []

        for message in prompt.get_messages():
            content = message["content"]
            role = message["role"]

            sentences = [s.strip() for s in content.split(".") if s.strip()]
            if len(sentences) <= 1:
                mutated_messages.append(
                    {"role": role, "content": self._word_level_mutation(content)}
                )
                continue

            mutation_type = random.random()
            if mutation_type < 0.3:
                random.shuffle(sentences)
                mutated_messages.append(
                    {"role": role, "content": ". ".join(sentences) + "."}
                )
                continue
            elif mutation_type < 0.6:
                if len(sentences) >= 2:
                    idx = random.randint(0, len(sentences) - 2)
                    combined = sentences[idx] + " and " + sentences[idx + 1]
                    sentences[idx : idx + 2] = [combined]
                    mutated_messages.append(
                        {"role": role, "content": ". ".join(sentences) + "."}
                    )
                    continue
            else:
                idx = random.randint(0, len(sentences) - 1)
                words = sentences[idx].split()
                if len(words) > 3:
                    split_point = random.randint(2, len(words) - 2)
                    sentences[idx : idx + 1] = [
                        " ".join(words[:split_point]),
                        " ".join(words[split_point:]),
                    ]
                    mutated_messages.append(
                        {"role": role, "content": ". ".join(sentences) + "."}
                    )
                    continue
                else:
                    mutated_messages.append({"role": role, "content": content})

        return chat_prompt.ChatPrompt(messages=mutated_messages)

    def _word_level_mutation_prompt(
        self, prompt: chat_prompt.ChatPrompt
    ) -> chat_prompt.ChatPrompt:
        mutated_messages: list[dict[str, str]] = []
        for message in prompt.get_messages():
            mutated_messages.append(
                {
                    "role": message["role"],
                    "content": self._word_level_mutation(message["content"]),
                }
            )
        return chat_prompt.ChatPrompt(messages=mutated_messages)

    def _word_level_mutation(self, msg_content: str) -> str:
        """Perform word-level mutation."""
        words = msg_content.split()
        if len(words) <= 1:
            return msg_content

        mutation_type = random.random()
        if mutation_type < 0.3:
            idx = random.randint(0, len(words) - 1)
            words[idx] = self._get_synonym(words[idx])
        elif mutation_type < 0.6:
            if len(words) > 2:
                i, j = random.sample(range(len(words)), 2)
                words[i], words[j] = words[j], words[i]
        else:
            idx = random.randint(0, len(words) - 1)
            words[idx] = self._modify_phrase(words[idx])

        return " ".join(words)

    def _get_synonym(self, word: str) -> str:
        """Get a synonym for a word using LLM."""
        try:
            response = self._call_model(
                messages=[
                    {"role": "system", "content": evo_prompts.synonyms_system_prompt()},
                    {
                        "role": "user",
                        "content": (
                            f"Give me a single synonym for the word '{word}'. Return only the synonym, nothing else."
                        ),
                    },
                ],
                is_reasoning=True,
            )
            return response.strip()
        except Exception as e:
            logger.warning(f"Error getting synonym for '{word}': {e}")
            return word

    def _modify_phrase(self, phrase: str) -> str:
        """Modify a phrase while preserving meaning using LLM."""
        try:
            response = self._call_model(
                messages=[
                    {"role": "system", "content": evo_prompts.rephrase_system_prompt()},
                    {
                        "role": "user",
                        "content": (
                            f"Modify this phrase while keeping the same meaning: '{phrase}'. Return only the modified phrase, nothing else."
                        ),
                    },
                ],
                is_reasoning=True,
            )
            return response.strip()
        except Exception as e:
            logger.warning(f"Error modifying phrase '{phrase}': {e}")
            return phrase

    def _radical_innovation_mutation(
        self, prompt: chat_prompt.ChatPrompt, initial_prompt: chat_prompt.ChatPrompt
    ) -> chat_prompt.ChatPrompt:
        """Attempts to generate a significantly improved and potentially very different prompt using an LLM."""
        logger.debug(
            f"Attempting radical innovation for prompt: {json.dumps(prompt.get_messages())[:70]}..."
        )
        task_desc_for_llm = self._get_task_description_for_llm(initial_prompt)
        current_output_style_guidance = self.output_style_guidance

        user_prompt_for_radical_innovation = evo_prompts.radical_innovation_user_prompt(
            task_desc_for_llm, current_output_style_guidance, prompt.get_messages()
        )
        try:
            new_prompt_str = self._call_model(
                messages=[
                    {
                        "role": "system",
                        "content": evo_prompts.radical_innovation_system_prompt(
                            current_output_style_guidance
                        ),
                    },
                    {"role": "user", "content": user_prompt_for_radical_innovation},
                ],
                is_reasoning=True,
            )
            logger.info(
                f"Radical innovation LLM result (truncated): {new_prompt_str[:200]}"
            )
            try:
                new_messages = utils.json_to_dict(new_prompt_str)
            except Exception as parse_exc:
                logger.warning(
                    f"Failed to parse LLM output in radical innovation mutation for prompt '{json.dumps(prompt.get_messages())[:50]}...'. Output: {new_prompt_str[:200]}. Error: {parse_exc}. Returning original."
                )
                return prompt
            return chat_prompt.ChatPrompt(messages=new_messages)
        except Exception as e:
            logger.warning(
                f"Radical innovation mutation failed for prompt '{json.dumps(prompt.get_messages())[:50]}...': {e}. Returning original."
            )
            return prompt
