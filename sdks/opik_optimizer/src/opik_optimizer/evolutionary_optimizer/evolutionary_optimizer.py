import json
import logging
import os
import random
from typing import Any, Callable, Dict, List, Literal, Optional, Set, Tuple, cast

import Levenshtein
import litellm
import numpy as np
import opik

# DEAP imports
from deap import base, tools
from deap import creator as _creator
from litellm import exceptions as litellm_exceptions
from litellm.caching import Cache
from litellm.types.caching import LiteLLMCacheType
from opik.api_objects import opik_client, optimization
from opik.environment import get_tqdm_for_current_environment
from opik.evaluation.models.litellm import opik_monitor as opik_litellm_monitor

from opik_optimizer import _throttle, task_evaluator
from opik_optimizer.base_optimizer import BaseOptimizer, OptimizationRound
from opik_optimizer.optimization_config import chat_prompt, mappers
from opik_optimizer.optimization_result import OptimizationResult

from .. import utils
from . import reporting

logger = logging.getLogger(__name__)
tqdm = get_tqdm_for_current_environment()
_rate_limiter = _throttle.get_rate_limiter_for_current_opik_installation()

# Using disk cache for LLM calls
disk_cache_dir = os.path.expanduser("~/.litellm_cache")
litellm.cache = Cache(type=LiteLLMCacheType.DISK, disk_cache_dir=disk_cache_dir)

creator = cast(Any, _creator)  # type: ignore[assignment]

class EvolutionaryOptimizer(BaseOptimizer):
    """
    The Evolutionary Optimizer can be used to optimize prompts using a 4 stage genetic algorithm
    approach:

    1. Generate a set of candidate prompts based on variations of the best prompts (exploitation) as
    well as completely new prompts (exploration)
    2. Evaluate the candidate prompts
    3. Select the best prompts
    4. Repeat until convergence
    
    This algorithm is best used if you have a first draft prompt and would like to find a better
    prompt.
    
    Note: This algorithm is time consuming and can be expensive to run.
    """

    DEFAULT_POPULATION_SIZE = 30
    DEFAULT_NUM_GENERATIONS = 15
    DEFAULT_MUTATION_RATE = 0.2
    DEFAULT_CROSSOVER_RATE = 0.8
    DEFAULT_TOURNAMENT_SIZE = 4
    DEFAULT_NUM_THREADS = 12
    DEFAULT_HALL_OF_FAME_SIZE = 10
    DEFAULT_ELITISM_SIZE = 3
    DEFAULT_MIN_MUTATION_RATE = 0.1
    DEFAULT_MAX_MUTATION_RATE = 0.4
    DEFAULT_ADAPTIVE_MUTATION = True
    DEFAULT_DIVERSITY_THRESHOLD = 0.7 
    DEFAULT_RESTART_THRESHOLD = 0.01 
    DEFAULT_RESTART_GENERATIONS = 3 
    DEFAULT_CACHE_SIZE = 1000 
    DEFAULT_EARLY_STOPPING_GENERATIONS = 5
    DEFAULT_ENABLE_MOO = True 
    DEFAULT_ENABLE_LLM_CROSSOVER = True 
    DEFAULT_SEED = 42
    DEFAULT_OUTPUT_STYLE_GUIDANCE = "Produce clear, effective, and high-quality responses suitable for the task."
    DEFAULT_MOO_WEIGHTS = (1.0, -1.0)  # (Maximize Score, Minimize Length)

    _INFER_STYLE_SYSTEM_PROMPT = """You are an expert in linguistic analysis and prompt engineering. Your task is to analyze a few input-output examples from a dataset and provide a concise, actionable description of the desired output style. This description will be used to guide other LLMs in generating and refining prompts.

Focus on characteristics like:
- **Length**: (e.g., single word, short phrase, one sentence, multiple sentences, a paragraph)
- **Tone**: (e.g., factual, formal, informal, conversational, academic)
- **Structure**: (e.g., direct answer first, explanation then answer, list, yes/no then explanation)
- **Content Details**: (e.g., includes only the answer, includes reasoning, provides examples, avoids pleasantries)
- **Keywords/Phrasing**: Any recurring keywords or phrasing patterns in the outputs.

Provide a single string that summarizes this style. This summary should be directly usable as an instruction for another LLM. 
For example: 'Outputs should be a single, concise proper noun.' OR 'Outputs should be a short paragraph explaining the reasoning, followed by a direct answer, avoiding conversational pleasantries.' OR 'Outputs are typically 1-2 sentences, providing a direct factual answer.'
Return ONLY this descriptive string, with no preamble or extra formatting.
"""

    def __init__(
        self,
        model: str,
        project_name: str = "Optimization",
        population_size: int = DEFAULT_POPULATION_SIZE,
        num_generations: int = DEFAULT_NUM_GENERATIONS,
        mutation_rate: float = DEFAULT_MUTATION_RATE,
        crossover_rate: float = DEFAULT_CROSSOVER_RATE,
        tournament_size: int = DEFAULT_TOURNAMENT_SIZE,
        num_threads: int = DEFAULT_NUM_THREADS,
        elitism_size: int = DEFAULT_ELITISM_SIZE,
        adaptive_mutation: bool = DEFAULT_ADAPTIVE_MUTATION,
        enable_moo: bool = DEFAULT_ENABLE_MOO,
        enable_llm_crossover: bool = DEFAULT_ENABLE_LLM_CROSSOVER,
        seed: Optional[int] = DEFAULT_SEED,
        output_style_guidance: Optional[str] = None,
        infer_output_style: bool = False,
        verbose: int = 1,
        **model_kwargs,
    ):
        """
        Args:
            model: The model to use for evaluation
            project_name: Optional project name for tracking
            population_size: Number of prompts in the population
            num_generations: Number of generations to run
            mutation_rate: Mutation rate for genetic operations
            crossover_rate: Crossover rate for genetic operations
            tournament_size: Tournament size for selection
            num_threads: Number of threads for parallel evaluation
            elitism_size: Number of elitism prompts
            adaptive_mutation: Whether to use adaptive mutation
            enable_moo: Whether to enable multi-objective optimization - When enable optimizes for both the supplied metric and the length of the prompt
            enable_llm_crossover: Whether to enable LLM crossover
            seed: Random seed for reproducibility
            output_style_guidance: Output style guidance for prompts
            infer_output_style: Whether to infer output style
            verbose: Controls internal logging/progress bars (0=off, 1=on).
            **model_kwargs: Additional model parameters
        """
        # Initialize base class first
        super().__init__(model=model, project_name=project_name, **model_kwargs)
        self.population_size = population_size
        self.num_generations = num_generations
        self.mutation_rate = mutation_rate
        self.crossover_rate = crossover_rate
        self.tournament_size = tournament_size
        self.num_threads = num_threads
        self.elitism_size = elitism_size
        self.adaptive_mutation = adaptive_mutation
        self.enable_moo = enable_moo
        self.enable_llm_crossover = enable_llm_crossover
        self.seed = seed
        self.output_style_guidance = output_style_guidance if output_style_guidance is not None else self.DEFAULT_OUTPUT_STYLE_GUIDANCE
        self.infer_output_style = infer_output_style
        self.llm_call_counter = 0
        self._opik_client = opik_client.get_client_cached()
        self._current_optimization_id = None
        self._current_generation = 0
        self._best_fitness_history = []
        self._generations_without_improvement = 0
        self._llm_cache = {}
        self._current_population = []
        self._generations_without_overall_improvement = 0
        self._best_primary_score_history: list[float] = []
        self._gens_since_pop_improvement: int = 0
        self.verbose = verbose

        if self.seed is not None:
            random.seed(self.seed)
            np.random.seed(self.seed)
            logger.info(f"Global random seed set to: {self.seed}")
            # Note: DEAP tools generally respect random.seed().
            # TODO investigate if specific DEAP components require separate seeding

        if self.enable_moo:
            if not hasattr(creator, "FitnessMulti"):
                creator.create("FitnessMulti", base.Fitness, weights=self.DEFAULT_MOO_WEIGHTS)
            fitness_attr = creator.FitnessMulti
        else:
            if not hasattr(creator, "FitnessMax"):
                creator.create("FitnessMax", base.Fitness, weights=(1.0,))
            fitness_attr = creator.FitnessMax
        
        if not hasattr(creator, "Individual") or getattr(creator.Individual, "fitness") != fitness_attr:
            if hasattr(creator, "Individual"):
                del creator.Individual
            creator.create("Individual", list, fitness=fitness_attr)

        self.toolbox = base.Toolbox()
        self.toolbox.register("default_individual", lambda: creator.Individual("placeholder"))
        self.toolbox.register("population", tools.initRepeat, list, self.toolbox.default_individual)
        
        if self.enable_llm_crossover:
            self.toolbox.register("mate", self._llm_deap_crossover)
        else:
            self.toolbox.register("mate", self._deap_crossover)
        
        self.toolbox.register("mutate", self._deap_mutation)
        
        if self.enable_moo:
            self.toolbox.register("select", tools.selNSGA2)
        else:
            self.toolbox.register("select", tools.selTournament, tournsize=self.tournament_size)

        logger.debug(
            f"Initialized EvolutionaryOptimizer with model: {model}, MOO_enabled: {self.enable_moo}, "
            f"LLM_Crossover: {self.enable_llm_crossover}, Seed: {self.seed}, "
            f"OutputStyleGuidance: '{self.output_style_guidance[:50]}...', "
            f"population_size: {self.population_size}, num_generations: {self.num_generations}, "
            f"mutation_rate: {self.mutation_rate}, crossover_rate: {self.crossover_rate}"
        )

    def _get_adaptive_mutation_rate(self) -> float:
        """Calculate adaptive mutation rate based on population diversity and progress."""
        if not self.adaptive_mutation or len(self._best_fitness_history) < 2:
            return self.mutation_rate

        # Calculate improvement rate
        recent_improvement = (self._best_fitness_history[-1] - self._best_fitness_history[-2]) / abs(self._best_fitness_history[-2])
        
        # Calculate population diversity
        current_diversity = self._calculate_population_diversity()
        
        # Check for stagnation
        if recent_improvement < self.DEFAULT_RESTART_THRESHOLD:
            self._generations_without_improvement += 1
        else:
            self._generations_without_improvement = 0
        
        # Adjust mutation rate based on both improvement and diversity
        if self._generations_without_improvement >= self.DEFAULT_RESTART_GENERATIONS:
            # Significant stagnation - increase mutation significantly
            return min(self.mutation_rate * 2.5, self.DEFAULT_MAX_MUTATION_RATE)
        elif recent_improvement < 0.01 and current_diversity < self.DEFAULT_DIVERSITY_THRESHOLD:
            # Both stagnating and low diversity - increase mutation significantly
            return min(self.mutation_rate * 2.0, self.DEFAULT_MAX_MUTATION_RATE)
        elif recent_improvement < 0.01:
            # Stagnating but good diversity - moderate increase
            return min(self.mutation_rate * 1.5, self.DEFAULT_MAX_MUTATION_RATE)
        elif recent_improvement > 0.05:
            # Good progress - decrease mutation
            return max(self.mutation_rate * 0.8, self.DEFAULT_MIN_MUTATION_RATE)
        return self.mutation_rate

    def _calculate_population_diversity(self) -> float:
        """Calculate the diversity of the current population."""
        if not hasattr(self, '_current_population') or not self._current_population:
            return 0.0
        
        # Calculate average Levenshtein distance between all pairs
        total_distance = 0.0
        count = 0
        for i in range(len(self._current_population)):
            for j in range(i + 1, len(self._current_population)):
                str1 = str(self._current_population[i])
                str2 = str(self._current_population[j])
                distance = Levenshtein.distance(str1, str2)
                max_len = max(len(str1), len(str2))
                if max_len > 0:
                    normalized_distance = distance / max_len
                    total_distance += normalized_distance
                    count += 1
        
        return total_distance / count if count > 0 else 0.0


    def _deap_crossover_chunking_strategy(self, messages_1_str: str, messages_2_str: str) -> Tuple[str, str]:
        chunks1 = [chunk.strip() for chunk in messages_1_str.split('.') if chunk.strip()]
        chunks2 = [chunk.strip() for chunk in messages_2_str.split('.') if chunk.strip()]

        # Try chunk-level crossover if both parents have at least 2 chunks
        if len(chunks1) >= 2 and len(chunks2) >= 2:
            min_num_chunks = min(len(chunks1), len(chunks2))
            # Crossover point is between 1 and min_num_chunks - 1
            # This requires min_num_chunks >= 2, which is already checked.
            point = random.randint(1, min_num_chunks - 1)
            
            child1_chunks = chunks1[:point] + chunks2[point:]
            child2_chunks = chunks2[:point] + chunks1[point:]
            
            child1_str = '. '.join(child1_chunks) + ('.' if child1_chunks else '')
            child2_str = '. '.join(child2_chunks) + ('.' if child2_chunks else '')
            
            return child1_str, child2_str
        else:
            raise ValueError("Not enough chunks in either prompt for chunk-level crossover")
    
    def _deap_crossover_word_level(self, messages_1_str: str, messages_2_str: str) -> Tuple[str, str]:
        words1 = messages_1_str.split()
        words2 = messages_2_str.split()

        # If either prompt is empty (no words), return parents
        if not words1 or not words2:
            return messages_1_str, messages_2_str

        min_word_len = min(len(words1), len(words2))
        # Need at least 2 words in the shorter prompt for a valid crossover point
        if min_word_len < 2:
            return messages_1_str, messages_2_str

        # Crossover point for words: 1 to min_word_len - 1
        point = random.randint(1, min_word_len - 1)
        child1_words = words1[:point] + words2[point:]
        child2_words = words2[:point] + words1[point:]
        
        return ' '.join(child1_words), ' '.join(child2_words)

    def _deap_crossover(
            self,
            ind1: "creator.Individual",
            ind2: "creator.Individual"
        ) -> Tuple["creator.Individual", "creator.Individual"]:
        """Enhanced crossover operation that preserves semantic meaning.
        Attempts chunk-level crossover first, then falls back to word-level.
        """
        reporting.display_message("      Recombining prompts by mixing and matching words and sentences.", verbose=self.verbose)
        messages_1_orig: List[Dict[Literal["role", "content"], str]] = ind1
        messages_2_orig: List[Dict[Literal["role", "content"], str]] = ind2

        for i, message_1 in enumerate(messages_1_orig):
            role: str = message_1['role']
            message_1_str: str = message_1['content']

            # We check that the second message has enough AI messages and the correct role
            if (len(messages_2_orig) >= i + 1) and (messages_2_orig[i]['role'] == role):
                message_2 = messages_2_orig[i]
                message_2_str: str = message_2['content']

                try:
                    child1_str, child2_str = self._deap_crossover_chunking_strategy(message_1_str, message_2_str)
                except ValueError:
                    child1_str, child2_str = self._deap_crossover_word_level(message_1_str, message_2_str)
                
                # Update the message content
                messages_1_orig[i]['content'] = child1_str
                messages_2_orig[i]['content'] = child2_str
            else:
                # We don't perform any crossover if there are not enough messages or the roles
                # don't match
                pass
        
        return creator.Individual(messages_1_orig), creator.Individual(messages_2_orig)

    def _deap_mutation(
            self,
            individual: "creator.Individual",
            initial_prompt: chat_prompt.ChatPrompt
        ) -> "creator.Individual":
        """Enhanced mutation operation with multiple strategies."""
        prompt = chat_prompt.ChatPrompt(messages=individual)
        
        # Choose mutation strategy based on current diversity
        diversity = self._calculate_population_diversity()

        # Determine thresholds based on diversity
        if diversity < self.DEFAULT_DIVERSITY_THRESHOLD:
            # Low diversity - use more aggressive mutations (higher chance for semantic)
            semantic_threshold = 0.5
            structural_threshold = 0.8 # semantic_threshold + 0.3
        else:
            # Good diversity - use more conservative mutations (higher chance for word_level)
            semantic_threshold = 0.4
            structural_threshold = 0.7 # semantic_threshold + 0.3

        mutation_choice = random.random()

        if mutation_choice > structural_threshold:
            # This corresponds to the original 'else' (word_level_mutation)
            mutated_prompt = self._word_level_mutation_prompt(prompt)
            reporting.display_success("      Mutation successful, prompt has been edited by randomizing words (word-level mutation).", verbose=self.verbose)
            return creator.Individual(mutated_prompt.formatted_messages)
        elif mutation_choice > semantic_threshold:
            # This corresponds to the original 'elif' (structural_mutation)
            mutated_prompt = self._structural_mutation(prompt)
            reporting.display_success("      Mutation successful, prompt has been edited by reordering, combining, or splitting sentences (structural mutation).", verbose=self.verbose)
            return creator.Individual(mutated_prompt.formatted_messages)
        else:
            # This corresponds to the original 'if' (semantic_mutation)
            mutated_prompt = self._semantic_mutation(prompt, initial_prompt)
            reporting.display_success("      Mutation successful, prompt has been edited using an LLM (semantic mutation).", verbose=self.verbose)
            return creator.Individual(mutated_prompt.formatted_messages)

    def _semantic_mutation(
            self,
            prompt: chat_prompt.ChatPrompt,
            initial_prompt: chat_prompt.ChatPrompt
        ) -> chat_prompt.ChatPrompt:
        """Enhanced semantic mutation with multiple strategies."""
        current_output_style_guidance = self.output_style_guidance
        if random.random() < 0.1: 
            return self._radical_innovation_mutation(prompt, initial_prompt)
        
        try:
            strategy = random.choice([
                "rephrase", "simplify", "elaborate", "restructure", "focus", "increase_complexity_and_detail"
            ])
            
            strategy_prompts = {
                "rephrase": f"Create a different way to express the same instruction, possibly with a different length or structure, ensuring it still aims for an answer from the target LLM in the style of: '{current_output_style_guidance}'.",
                "simplify": f"Simplify the instruction while maintaining its core meaning, potentially making it more concise, to elicit an answer in the style of: '{current_output_style_guidance}'.",
                "elaborate": f"Add more relevant detail and specificity to the instruction, potentially increasing its length, but only if it helps achieve a more accurate answer from the target LLM in the style of: '{current_output_style_guidance}'.",
                "restructure": f"Change the structure of the instruction (e.g., reorder sentences, combine/split ideas) while keeping its intent, ensuring the new structure strongly guides towards an output in the style of: '{current_output_style_guidance}'.",
                "focus": f"Emphasize the key aspects of the instruction, perhaps by rephrasing or adding clarifying statements, to better elicit an answer in the style of: '{current_output_style_guidance}'.",
                "increase_complexity_and_detail": f"Significantly elaborate on this instruction. Add more details, examples, context, or constraints to make it more comprehensive. The goal of this elaboration is to make the prompt itself more detailed, so that it VERY CLEARLY guides the target LLM to produce a highly accurate final answer in the style of: '{current_output_style_guidance}'. The prompt can be long if needed to achieve this output style."
            }
            
            user_prompt_for_semantic_mutation = f"""Given this prompt: '{prompt}'
Task context: {self._get_task_description_for_llm(initial_prompt)}
Desired output style from target LLM: '{current_output_style_guidance}'
Instruction for this modification: {strategy_prompts[strategy]}.
Return only the modified prompt message list, nothing else. Make sure to return a valid JSON object.
"""
            response = self._call_model(
                messages=[
                    {"role": "system", "content": f"You are a prompt engineering expert. Your goal is to modify prompts to improve their effectiveness in eliciting specific types of answers, particularly matching the style: '{current_output_style_guidance}'. Follow the specific modification instruction provided."},
                    {"role": "user", "content": user_prompt_for_semantic_mutation}
                ],
                is_reasoning=True
            )

            return chat_prompt.ChatPrompt(messages=utils.json_to_dict(response.strip()))
        except Exception as e:
            reporting.display_error(f"      Error in semantic mutation, this is usually a parsing error: {e}", verbose=self.verbose)
            return prompt

    def _structural_mutation(
            self,
            prompt: chat_prompt.ChatPrompt
        ) -> chat_prompt.ChatPrompt:
        """Perform structural mutation (reordering, combining, splitting)."""
        mutated_messages: List[Dict[Literal["role", "content"], str]] = []

        for message in prompt.formatted_messages:
            content = message["content"]
            role = message["role"]

            sentences = [s.strip() for s in content.split('.') if s.strip()]
            if len(sentences) <= 1:
                mutated_messages.append({"role": role, "content": self._word_level_mutation(content)})
                continue
            
            mutation_type = random.random()
            if mutation_type < 0.3:
                # Reorder sentences
                random.shuffle(sentences)
                mutated_messages.append({"role": role, "content": '. '.join(sentences) + '.'})
                continue
            elif mutation_type < 0.6:
                # Combine adjacent sentences
                if len(sentences) >= 2:
                    idx = random.randint(0, len(sentences) - 2)
                    combined = sentences[idx] + ' and ' + sentences[idx + 1]
                    sentences[idx:idx+2] = [combined]
                    mutated_messages.append({"role": role, "content": '. '.join(sentences) + '.'})
                    continue
            else:
                # Split a sentence
                idx = random.randint(0, len(sentences) - 1)
                words = sentences[idx].split()
                if len(words) > 3:
                    split_point = random.randint(2, len(words) - 2)
                    sentences[idx:idx+1] = [' '.join(words[:split_point]), ' '.join(words[split_point:])]
                    mutated_messages.append({"role": role, "content": '. '.join(sentences) + '.'})
                    continue
                else:
                    mutated_messages.append({"role": role, "content": content})

        return chat_prompt.ChatPrompt(messages=mutated_messages)

    def _word_level_mutation_prompt(self, prompt: chat_prompt.ChatPrompt) -> chat_prompt.ChatPrompt:
        mutated_messages: List[Dict[Literal['role', 'content'], str]] = []
        for message in prompt.formatted_messages:
            mutated_messages.append({"role": message["role"], "content": self._word_level_mutation(message["content"])})
        return chat_prompt.ChatPrompt(messages=mutated_messages)
    
    def _word_level_mutation(self, msg_content: str) -> str:
        """Perform word-level mutation."""
        words = msg_content.split()
        if len(words) <= 1:
            return msg_content
        
        mutation_type = random.random()
        if mutation_type < 0.3: 
            # Word replacement
            idx = random.randint(0, len(words) - 1)
            words[idx] = self._get_synonym(words[idx])
        elif mutation_type < 0.6:
             # Word reordering
            if len(words) > 2:
                i, j = random.sample(range(len(words)), 2)
                words[i], words[j] = words[j], words[i]
        else:
            # Phrase modification
            idx = random.randint(0, len(words) - 1)
            words[idx] = self._modify_phrase(words[idx])
        
        return ' '.join(words)

    def _get_synonym(
            self,
            word: str
        ) -> str:
        """Get a synonym for a word using LLM."""
        try:
            response = self._call_model(
                messages=[
                    {"role": "system", "content": "You are a helpful assistant that provides synonyms. Return only the synonym word, no explanation or additional text."},
                    {"role": "user", "content": f"Give me a single synonym for the word '{word}'. Return only the synonym, nothing else."}
                ],
                is_reasoning=True
            )
            return response.strip()
        except Exception as e:
            logger.warning(f"Error getting synonym for '{word}': {e}")
            return word

    def _modify_phrase(
            self,
            phrase: str
        ) -> str:
        """Modify a phrase while preserving meaning using LLM."""
        try:
            response = self._call_model(
                messages=[
                    {"role": "system", "content": "You are a helpful assistant that rephrases text. Return only the modified phrase, no explanation or additional text."},
                    {"role": "user", "content": f"Modify this phrase while keeping the same meaning: '{phrase}'. Return only the modified phrase, nothing else."}
                ],
                is_reasoning=True
            )
            return response.strip()
        except Exception as e:
            logger.warning(f"Error modifying phrase '{phrase}': {e}")
            return phrase

    def _radical_innovation_mutation(
            self,
            prompt: chat_prompt.ChatPrompt,
            initial_prompt: chat_prompt.ChatPrompt
        ) -> chat_prompt.ChatPrompt:
        """Attempts to generate a significantly improved and potentially very different prompt using an LLM."""
        logger.debug(f"Attempting radical innovation for prompt: {json.dumps(prompt.formatted_messages)[:70]}...")
        task_desc_for_llm = self._get_task_description_for_llm(initial_prompt)
        current_output_style_guidance = self.output_style_guidance
        
        user_prompt_for_radical_innovation = f"""Task Context:
{task_desc_for_llm}
Desired output style from target LLM: '{current_output_style_guidance}'

Existing Prompt (which may be underperforming):
'''{prompt.formatted_messages}'''

Please generate a new, significantly improved, and potentially very different prompt for this task. 
Focus on alternative approaches, better clarity, or more effective guidance for the language model, aiming for the desired output style.
Return only the new prompt list object.
"""
        try:
            new_prompt_str = self._call_model(
                messages=[
                    {"role": "system", "content": self._get_radical_innovation_system_prompt()},
                    {"role": "user", "content": user_prompt_for_radical_innovation}
                ],
                is_reasoning=True
            )
            logger.info(f"Radical innovation generated: {new_prompt_str[:70]}... from: {json.dumps(prompt.formatted_messages)[:70]}...")
            return chat_prompt.ChatPrompt(messages=json.loads(new_prompt_str))
        except Exception as e:
            logger.warning(f"Radical innovation mutation failed for prompt '{json.dumps(prompt.formatted_messages)[:50]}...': {e}. Returning original.")
            return prompt

    def _initialize_population(
        self,
        prompt: chat_prompt.ChatPrompt
    ) -> List[chat_prompt.ChatPrompt]:
        """Initialize the population with diverse variations of the initial prompt, 
           including some 'fresh start' prompts based purely on task description.
           All generated prompts should aim to elicit answers matching self.output_style_guidance.
        """
        with reporting.initializing_population(verbose=self.verbose) as init_pop_report:
            init_pop_report.start(self.population_size)

            population = [prompt]
            if self.population_size <= 1:
                return population

            num_to_generate_total = self.population_size - 1
            num_fresh_starts = max(1, int(num_to_generate_total * 0.2))
            num_variations_on_initial = num_to_generate_total - num_fresh_starts

            task_desc_for_llm = self._get_task_description_for_llm(prompt)
            current_output_style_guidance = self.output_style_guidance

            # Generate "fresh start" prompts if the initial prompt is not performing well
            # Cold start prompts are generated from the task description
            if num_fresh_starts > 0:
                init_pop_report.start_fresh_prompts(num_fresh_starts)
                fresh_start_user_prompt = f"""Here is a description of a task:
    {task_desc_for_llm}

    The goal is to generate prompts that will make a target LLM produce responses in the following style: '{current_output_style_guidance}'.

    Please generate {num_fresh_starts} diverse and effective prompt(s) for a language model to accomplish this task, ensuring they guide towards this specific output style.
    Focus on clarity, completeness, and guiding the model effectively towards the desired style. Explore different structural approaches.
    
    Example of valid response: [
        ["role": "<role>", "content": "<Prompt targeting specified style.>"],
        ["role": "<role>", "content": "<Another prompt designed for the output style.>"]
    ]

    Your response MUST be a valid JSON list of AI messages. Do NOT include any other text, explanations, or Markdown formatting like ```json ... ``` around the list.
    
    """
                try:
                    response_content = self._call_model(
                        messages=[
                            {"role": "system", "content": f"You are an expert prompt engineer. Your task is to generate novel, effective prompts from scratch based on a task description, specifically aiming for prompts that elicit answers in the style: '{current_output_style_guidance}'. Output ONLY a raw JSON list of strings."},
                            {"role": "user", "content": fresh_start_user_prompt}
                        ],
                        is_reasoning=True
                    )
                    
                    logger.debug(f"Raw LLM response for fresh start prompts: {response_content}")
                    
                    fresh_prompts = utils.json_to_dict(response_content)
                    if isinstance(fresh_prompts, list):
                        if all(isinstance(p, dict) for p in fresh_prompts) and all(p.get("role") is not None for p in fresh_prompts):
                            population.append(chat_prompt.ChatPrompt(messages=fresh_prompts))
                            init_pop_report.success_fresh_prompts(1)
                        elif all(isinstance(p, list) for p in fresh_prompts):
                            population.extend([chat_prompt.ChatPrompt(messages=p) for p in fresh_prompts[:num_fresh_starts]])
                            init_pop_report.success_fresh_prompts(len(fresh_prompts[:num_fresh_starts]))
                        else:
                            init_pop_report.failed_fresh_prompts(
                                num_fresh_starts,
                                f"LLM response for fresh starts was not a valid list of strings or was empty: {response_content}. Skipping fresh start prompts."
                            )
                except json.JSONDecodeError as e_json:
                    init_pop_report.failed_fresh_prompts(
                        num_fresh_starts,
                        f"JSONDecodeError generating fresh start prompts: {e_json}. LLM response: '{response_content}'. Skipping fresh start prompts."
                    )
                except Exception as e:
                    init_pop_report.failed_fresh_prompts(
                        num_fresh_starts,
                        f"Error generating fresh start prompts: {e}. Skipping fresh start prompts."
                    )

            # Generate variations on the initial prompt for the remaining slots
            # TODO: Could add variations with hyper-parameters from the task config like temperature, etc.
            if num_variations_on_initial > 0:
                init_pop_report.start_variations(num_variations_on_initial)
                
                # TODO: We need to split this into batches as the model will not return enough tokens
                # to generate all the candidates
                user_prompt_for_variation = f"""Initial prompt:
    '''{prompt.formatted_messages}'''

    Task context:
    {task_desc_for_llm}
    Desired output style from target LLM: '{current_output_style_guidance}'

    Generate {num_variations_on_initial} diverse alternative prompts based on the initial prompt above, keeping the task context and desired output style in mind.
    All generated prompt variations should strongly aim to elicit answers from the target LLM matching the style: '{current_output_style_guidance}'.
    For each variation, consider how to best achieve this style, e.g., by adjusting specificity, structure, phrasing, constraints, or by explicitly requesting it.

    Return a JSON array of prompts with the following structure:
    {{
        "prompts": [
            {{
                "prompt": [{{"role": "<role>", "content": "<content>"}}],
                "strategy": "brief description of the variation strategy used, e.g., 'direct instruction for target style'"
            }}
            // ... more prompts if num_variations_on_initial > 1
        ]
    }}
    Ensure a good mix of variations, all targeting the specified output style from the end LLM.

    Return a valid JSON object that is correctly escaped. Return nothing else, d`o not include any additional text or Markdown formatting.
    """
                try:
                    response_content_variations = self._call_model(
                        messages=[
                            {"role": "system", "content": self._get_reasoning_system_prompt_for_variation()},
                            {"role": "user", "content": user_prompt_for_variation}
                        ],
                        is_reasoning=True
                    )
                    logger.debug(f"Raw response for population variations: {response_content_variations}")
                    json_response_variations = json.loads(response_content_variations)
                    generated_prompts_variations = [p["prompt"] for p in json_response_variations.get("prompts", []) if isinstance(p, dict) and "prompt" in p]
                    
                    if generated_prompts_variations:
                        init_pop_report.success_variations(len(generated_prompts_variations[:num_variations_on_initial]))
                        population.extend([chat_prompt.ChatPrompt(messages=p) for p in generated_prompts_variations[:num_variations_on_initial]])
                    else:
                        init_pop_report.failed_variations(num_variations_on_initial, "Could not parse 'prompts' list for variations. Skipping variations.")
                except Exception as e:
                    init_pop_report.failed_variations(num_variations_on_initial, f"Error calling LLM for initial population variations: {e}")

            # Ensure population is of the required size using unique prompts
            # TODO Test with levenshtein distance
            final_population_set: Set[str] = set()
            final_population_list: List[chat_prompt.ChatPrompt] = []
            for p in population:
                if json.dumps(p.formatted_messages) not in final_population_set:
                    final_population_set.add(json.dumps(p.formatted_messages))
                    final_population_list.append(p)
            
            init_pop_report.end(final_population_list)
            # Return exactly population_size prompts if possible, or fewer if generation failed badly.
            return final_population_list[:self.population_size]


    def _should_restart_population(self, curr_best: float) -> bool:
        """
        Update internal counters and decide if we should trigger
        a population restart based on lack of improvement.
        """
        if self._best_primary_score_history:
            threshold = self._best_primary_score_history[-1] * (1 + self.DEFAULT_RESTART_THRESHOLD)
            if curr_best < threshold:
                self._gens_since_pop_improvement += 1
            else:
                self._gens_since_pop_improvement = 0
        self._best_primary_score_history.append(curr_best)
        return self._gens_since_pop_improvement >= self.DEFAULT_RESTART_GENERATIONS

    def _restart_population(
        self,
        hof: tools.HallOfFame,
        population: list["creator.Individual"],
        best_prompt_so_far: chat_prompt.ChatPrompt,
    ) -> list["creator.Individual"]:
        """Return a fresh, evaluated population seeded by elites."""
        if self.enable_moo:
            elites = list(hof)
        else:
            elites = tools.selBest(population, self.elitism_size)

        seed_prompt = (
            chat_prompt.ChatPrompt(messages=max(elites, key=lambda x: x.fitness.values[0]))
            if elites else best_prompt_so_far
        )

        prompt_variants = self._initialize_population(seed_prompt)
        new_pop = [creator.Individual(p.formatted_messages) for p in prompt_variants]

        for ind, fit in zip(new_pop, map(self.toolbox.evaluate, new_pop)):
            ind.fitness.values = fit

        self._gens_since_pop_improvement = 0
        return new_pop

    def _run_generation(
        self,
        generation_idx: int,
        population: list["creator.Individual"],
        prompt: chat_prompt.ChatPrompt,
        hof: tools.HallOfFame,
        report: Any,
        best_primary_score_overall: float,
    ) -> tuple[list["creator.Individual"], int]:
        """Execute mating, mutation, evaluation and HoF update."""
        best_gen_score = 0.0

        # --- selection -------------------------------------------------
        if self.enable_moo:
            offspring = self.toolbox.select(population, self.population_size)
        else:
            elites = tools.selBest(population, self.elitism_size)
            rest   = self.toolbox.select(population, len(population) - self.elitism_size)
            offspring = elites + rest

        # --- crossover -------------------------------------------------
        report.performing_crossover()
        offspring = list(map(self.toolbox.clone, offspring))
        for i in range(0, len(offspring), 2):
            if i+1 < len(offspring):
                c1, c2 = offspring[i], offspring[i+1]
                if random.random() < self.crossover_rate:
                    c1_new, c2_new = self.toolbox.mate(c1, c2)
                    offspring[i], offspring[i+1] = c1_new, c2_new
                    del offspring[i].fitness.values, offspring[i+1].fitness.values
        reporting.display_success("      Crossover successful, prompts have been combined and edited.\n│", verbose=self.verbose)

        # --- mutation --------------------------------------------------
        report.performing_mutation()
        mut_rate = self._get_adaptive_mutation_rate()
        n_mutations = 0
        for i, ind in enumerate(offspring):
            if random.random() < mut_rate:
                new_ind = self.toolbox.mutate(ind, initial_prompt=prompt)
                offspring[i] = new_ind
                del offspring[i].fitness.values
                n_mutations += 1
        reporting.display_success(f"      Mutation successful, {n_mutations} prompts have been edited.\n│", verbose=self.verbose)
        
        # --- evaluation ------------------------------------------------
        invalid = [ind for ind in offspring if not ind.fitness.valid]
        report.performing_evaluation(len(invalid))
        for ind_idx, ind in enumerate(invalid):
            fit = self.toolbox.evaluate(ind)
            ind.fitness.values = fit
            best_gen_score = max(best_gen_score, fit[0])

            report.performed_evaluation(ind_idx, ind.fitness.values[0])
        
        # --- update HoF & reporter ------------------------------------
        hof.update(offspring)
        reporting.end_gen(generation_idx, best_gen_score, best_primary_score_overall, verbose=self.verbose)
        
        return offspring, len(invalid)

    def _population_best_score(self, population: List["creator.Individual"]) -> float:
        """Return highest primary-objective score among *valid* individuals."""
        valid_scores = [ind.fitness.values[0] for ind in population if ind.fitness.valid]
        return max(valid_scores, default=0.0)

    def optimize_prompt(
        self,
        prompt: chat_prompt.ChatPrompt,
        dataset: opik.Dataset,
        metric: Callable,
        experiment_config: Optional[Dict] = None,
        n_samples: Optional[int] = None,
        auto_continue: bool = False,
        **kwargs,
    ) -> OptimizationResult:
        """
        Args:
            prompt: The prompt to optimize
            dataset: The dataset to use for evaluation
            metric: Metric function to optimize with, should have the arguments `dataset_item` and `llm_output`
            experiment_config: Optional experiment configuration
            n_samples: Optional number of samples to use
            auto_continue: Whether to automatically continue optimization
            **kwargs: Additional keyword arguments
        """
        if not isinstance(prompt, chat_prompt.ChatPrompt):
            raise ValueError("Prompt must be a ChatPrompt object")
        
        if not isinstance(dataset, opik.Dataset):
            raise ValueError("Dataset must be a Dataset object")
        
        if not isinstance(metric, Callable):
            raise ValueError("Metric must be a function that takes `dataset_item` and `llm_output` as arguments.")

        # Step 0. Start Opik optimization run
        opik_optimization_run: Optional[optimization.Optimization] = None
        try:
            opik_optimization_run: optimization.Optimization = self._opik_client.create_optimization(
                dataset_name=dataset.name,
                objective_name=metric.__name__,
                metadata={"optimizer": self.__class__.__name__},
            )
            self._current_optimization_id = opik_optimization_run.id
        except Exception as e:
            logger.warning(f"Opik server error: {e}. Continuing without Opik tracking.")
            self._current_optimization_id = None

        reporting.display_header(
            algorithm=self.__class__.__name__,
            optimization_id=self._current_optimization_id,
            dataset_id=dataset.id,
            verbose=self.verbose
        )

        reporting.display_configuration(
            prompt.formatted_messages,
            {
                "optimizer": f"{ 'DEAP MOO' if self.enable_moo else 'DEAP SO' } Evolutionary Optimization",
                "population_size": self.population_size,
                "generations": self.num_generations,
                "mutation_rate": self.mutation_rate,
                "crossover_rate": self.crossover_rate,
            },
            verbose=self.verbose
        )

        # Step 1. Step variables and define fitness function
        self.llm_call_counter = 0
        self._history = []
        self._current_generation = 0
        self._best_fitness_history = []
        self._generations_without_improvement = 0
        self._llm_cache.clear()
        self._current_population = []
        self._generations_without_overall_improvement = 0
        
        if self.enable_moo:
            def _deap_evaluate_individual_fitness(
                    messages: List[Dict[str, str]]
                ) -> Tuple[float, float]:
                primary_fitness_score: float = self.evaluate_prompt(
                    prompt=chat_prompt.ChatPrompt(messages=messages),
                    dataset=dataset,
                    metric=metric,
                    n_samples=n_samples,
                    experiment_config=(experiment_config or {}).copy(),
                    optimization_id=self._current_optimization_id,
                    verbose=0
                )
                prompt_length = float(len(str(json.dumps(messages))))
                return (primary_fitness_score, prompt_length)
        else:
            # Single-objective
            def _deap_evaluate_individual_fitness(
                    messages: List[Dict[str, str]]
                ) -> Tuple[float,]:
                fitness_score: float = self.evaluate_prompt(
                    prompt=chat_prompt.ChatPrompt(messages=messages),
                    dataset=dataset,
                    metric=metric,
                    n_samples=n_samples,
                    experiment_config=(experiment_config or {}).copy(),
                    optimization_id=self._current_optimization_id,
                    verbose=0
                )
                return (fitness_score,)
        self.toolbox.register("evaluate", _deap_evaluate_individual_fitness)

        # Step 2. Compute the initial performance of the prompt
        with reporting.baseline_performance(verbose=self.verbose) as report_baseline_performance:
            initial_eval_result: Tuple[float, float] | Tuple[float, ] = _deap_evaluate_individual_fitness(prompt.formatted_messages)
            initial_primary_score: float = initial_eval_result[0]
            initial_length: float = initial_eval_result[1] if self.enable_moo else float(len(json.dumps(prompt.formatted_messages)))
            
            best_primary_score_overall: float = initial_primary_score
            best_prompt_overall = prompt
            report_baseline_performance.set_score(initial_primary_score)
        
        # Step 3. Define the output style guide
        effective_output_style_guidance = self.output_style_guidance
        if self.infer_output_style and \
        (self.output_style_guidance is None or self.output_style_guidance == self.DEFAULT_OUTPUT_STYLE_GUIDANCE):
            # If user wants inference AND hasn't provided a specific custom guidance
            inferred_style = self._infer_output_style_from_dataset(dataset, prompt)
            if inferred_style:
                effective_output_style_guidance = inferred_style
                # Update self.output_style_guidance for this run so dynamic prompt methods use it
                self.output_style_guidance = inferred_style
            else:
                logger.warning("Failed to infer output style, using default or user-provided guidance.")

        # Ensure self.output_style_guidance is set to the effective one for the rest of the methods for this run
        # (It might have been None if user passed None and infer_output_style was False)
        if self.output_style_guidance is None:
            # Fallback if still None
            self.output_style_guidance = self.DEFAULT_OUTPUT_STYLE_GUIDANCE
        
        # Step 4. Initialize population
        initial_prompts: List[chat_prompt.ChatPrompt] = self._initialize_population(
            prompt=prompt
        )
        
        deap_population = [creator.Individual(p.formatted_messages) for p in initial_prompts]
        deap_population = deap_population[:self.population_size]
        
        # Step 5. Initialize the hall of fame (Pareto front for MOO) and stats for MOO or SO
        if self.enable_moo:
            hof = tools.ParetoFront()
        else:
            # Single-objective
            hof = tools.HallOfFame(self.DEFAULT_HALL_OF_FAME_SIZE)

        # Step 6. Evaluate the initial population
        with reporting.evaluate_initial_population(verbose=self.verbose) as report_initial_population:
            fitnesses: List[float] = list(map(self.toolbox.evaluate, deap_population))
            _best_score = max(best_primary_score_overall, max([x[0] for x in fitnesses]))

            for i, ind, fit in zip(range(len(deap_population)), deap_population, fitnesses):
                ind.fitness.values = fit
                report_initial_population.set_score(i, fit[0], _best_score)
        
        hof.update(deap_population)
        
        if hof and len(hof) > 0:
            if self.enable_moo:
                current_best_for_primary: creator.Individual = max(hof, key=lambda ind: ind.fitness.values[0])
                best_primary_score_overall: float = current_best_for_primary.fitness.values[0]
                best_prompt_overall = chat_prompt.ChatPrompt(messages=current_best_for_primary)
            else:
                # Single-objective
                current_best_on_front = hof[0]
                best_primary_score_overall: float = current_best_on_front.fitness.values[0]
            
            if self.enable_moo:
                logger.info(f"Gen {0}: New best primary score: {best_primary_score_overall:.4f}, Prompt: {json.dumps(best_prompt_overall.formatted_messages)[:100]}...")
            else:
                logger.info(f"Gen {0}: New best score: {best_primary_score_overall:.4f}")

            # Simplified history logging for this transition
            initial_round_data = OptimizationRound(
                round_number=0,
                current_prompt=best_prompt_overall, # Representative best
                current_score=best_primary_score_overall,
                generated_prompts=[{"prompt": best_prompt_overall, "score": best_primary_score_overall, "trial_scores": [best_primary_score_overall]}],
                best_prompt=best_prompt_overall,
                best_score=best_primary_score_overall,
                improvement=0.0
            ).model_dump()
            self._add_to_history(initial_round_data)

        with reporting.start_evolutionary_algo(verbose=self.verbose) as report_evolutionary_algo:
            for generation_idx in range(1, self.num_generations + 1):
                report_evolutionary_algo.start_gen(generation_idx, self.num_generations)

                curr_best_score = self._population_best_score(deap_population)

                # ---------- restart logic -------------------------------------
                if self._should_restart_population(curr_best_score):
                    report_evolutionary_algo.restart_population(self.DEFAULT_RESTART_GENERATIONS)
                    deap_population = self._restart_population(
                        hof, deap_population, best_prompt_overall
                    )

                # ---------- run one generation --------------------------------
                deap_population, invalid_count = self._run_generation(
                    generation_idx, deap_population, prompt, hof, report_evolutionary_algo, best_primary_score_overall
                )

                # -------- update best-prompt bookkeeping -------------------------
                previous_best_primary_score_for_gen = best_primary_score_overall
                if hof:
                    if self.enable_moo:
                        current_best_ind = max(hof, key=lambda ind: ind.fitness.values[0])
                    else:
                        current_best_ind = hof[0]

                    updated_best_primary_score = current_best_ind.fitness.values[0]
                    if updated_best_primary_score > best_primary_score_overall:
                        best_primary_score_overall = updated_best_primary_score
                        self._generations_without_overall_improvement = 0
                    elif updated_best_primary_score == previous_best_primary_score_for_gen:
                        self._generations_without_overall_improvement += 1
                    else:
                        self._generations_without_overall_improvement += 1
                else:
                    self._generations_without_overall_improvement += 1

                # ---------- early-stopping check ------------------------------
                if self._generations_without_overall_improvement >= self.DEFAULT_EARLY_STOPPING_GENERATIONS:
                    logger.info(
                        "No overall improvement for %d generations – early stopping at gen %d.",
                        self.DEFAULT_EARLY_STOPPING_GENERATIONS,
                        generation_idx,
                    )
                    break

                # History logging for this transition
                # FIXME: Use model.dump() instead of dict()
                gen_round_data = OptimizationRound(
                    round_number=generation_idx,
                    current_prompt=best_prompt_overall, # Representative best
                    current_score=best_primary_score_overall,
                    generated_prompts=[{"prompt": str(ind), "score": ind.fitness.values[0]} for ind in deap_population if ind.fitness.valid],
                    best_prompt=best_prompt_overall,
                    best_score=best_primary_score_overall,
                    improvement=(best_primary_score_overall - initial_primary_score) / abs(initial_primary_score) if initial_primary_score and initial_primary_score != 0 else (1.0 if best_primary_score_overall > 0 else 0.0)
                ).model_dump()
                self._add_to_history(gen_round_data)

        stopped_early_flag = self._generations_without_overall_improvement >= self.DEFAULT_EARLY_STOPPING_GENERATIONS
        final_details = {}
        initial_score_for_display = initial_primary_score

        if self.enable_moo:
            final_results_log = "Pareto Front Solutions:\n"
            if hof and len(hof) > 0:
                sorted_hof = sorted(hof, key=lambda ind: ind.fitness.values[0], reverse=True)
                for i, sol in enumerate(sorted_hof):
                    final_results_log += f"  Solution {i+1}: Primary Score={sol.fitness.values[0]:.4f}, Length={sol.fitness.values[1]:.0f}, Prompt='{str(sol)[:100]}...'\n"
                best_overall_solution = sorted_hof[0]
                final_best_prompt = chat_prompt.ChatPrompt(messages=best_overall_solution)
                final_primary_score = best_overall_solution.fitness.values[0]
                final_length = best_overall_solution.fitness.values[1]
                logger.info(final_results_log)
                logger.info(f"Representative best prompt (highest primary score from Pareto front): '{final_best_prompt}'")
                logger.info(f"  Primary Score ({metric.__name__}): {final_primary_score:.4f}")
                logger.info(f"  Length: {final_length:.0f}")
                final_details.update({
                    "initial_primary_score": initial_primary_score,
                    "initial_length": initial_length,
                    "final_prompt_representative": final_best_prompt,
                    "final_primary_score_representative": final_primary_score,
                    "final_length_representative": final_length,
                    "pareto_front_solutions": [
                        {"prompt": str(ind), "score": ind.fitness.values[0], "length": ind.fitness.values[1]}
                        for ind in hof
                    ] if hof else []
                })
            else:
                # MOO: ParetoFront is empty. Reporting last known best and fallback values
                logger.warning("MOO: ParetoFront is empty. Reporting last known best.")
                final_best_prompt = best_prompt_overall
                final_primary_score = best_primary_score_overall
                final_length = float(len(json.dumps(final_best_prompt.formatted_messages)))
                final_details.update({"initial_primary_score": initial_primary_score, "initial_length": initial_length, 
                                      "final_prompt_representative": final_best_prompt, "final_primary_score_representative": final_primary_score, 
                                      "final_length_representative": final_length, "pareto_front_solutions": []})
        else:
            # Single-objective
            final_best_prompt = best_prompt_overall
            final_primary_score = best_primary_score_overall
            logger.info(f"Final best prompt from Hall of Fame: '{final_best_prompt}'")
            logger.info(f"Final best score ({metric.__name__}): {final_primary_score:.4f}")
            final_details.update({
                "initial_prompt": prompt.formatted_messages,
                "initial_score": initial_primary_score,
                "initial_score_for_display": initial_primary_score,
                "final_prompt": final_best_prompt,
                "final_score": final_primary_score,
            })
        
        logger.info(f"Total LLM calls during optimization: {self.llm_call_counter}")
        if opik_optimization_run:
            try:
                opik_optimization_run.update(status="completed")
                logger.info(f"Opik Optimization run {self._current_optimization_id} status updated to completed.")
            except Exception as e:
                logger.warning(f"Failed to update Opik Optimization run status: {e}")

        # Add final details
        final_details.update({
            "total_generations_run": generation_idx + 1,
            "num_generations": self.num_generations,
            "population_size": self.population_size,
            "mutation_probability": self.mutation_rate,
            "crossover_probability": self.crossover_rate,
            "elitism_size": self.elitism_size if not self.enable_moo else "N/A (MOO uses NSGA-II)",
            "adaptive_mutation": self.adaptive_mutation,
            "metric_name": metric.__name__,
            "model": self.model,
            "moo_enabled": self.enable_moo,
            "llm_crossover_enabled": self.enable_llm_crossover,
            "seed": self.seed,
            "prompt_type": "single_string_ga",
            "initial_score_for_display": initial_score_for_display,
            "temperature": self.model_kwargs.get("temperature"),
            "stopped_early": stopped_early_flag,
            "rounds": self.get_history(),
            "user_output_style_guidance": self.output_style_guidance,
            "infer_output_style_requested": self.infer_output_style,
            "final_effective_output_style_guidance": effective_output_style_guidance,
            "infer_output_style": self.infer_output_style,
        })

        # Return the OptimizationResult
        reporting.display_result(
            initial_score=initial_score_for_display,
            best_score=final_primary_score,
            best_prompt=final_best_prompt.formatted_messages,
            verbose=self.verbose
        )
        return OptimizationResult(
            optimizer=self.__class__.__name__,
            prompt=final_best_prompt.formatted_messages, 
            score=final_primary_score,
            initial_prompt=prompt.formatted_messages,
            initial_score=initial_primary_score,
            metric_name=metric.__name__,
            details=final_details,
            history=self.get_history(),
            llm_calls=self.llm_call_counter
        )

    @_throttle.rate_limited(_rate_limiter)
    def _call_model(
        self,
        messages: List[Dict[str, str]],
        is_reasoning: bool = False,
        optimization_id: Optional[str] = None,
    ) -> str:
        """Call the model with the given prompt and return the response."""
        try:
            # Basic LLM parameters
            llm_config_params = {
                "temperature": getattr(self, "temperature", 0.3),
                "max_tokens": getattr(self, "max_tokens", 1000),
                "top_p": getattr(self, "top_p", 1.0),
                "frequency_penalty": getattr(self, "frequency_penalty", 0.0),
                "presence_penalty": getattr(self, "presence_penalty", 0.0),
            }

            # Prepare metadata for opik
            metadata_for_opik = {}
            if self.project_name:
                metadata_for_opik["project_name"] = self.project_name
                metadata_for_opik["opik"] = {"project_name": self.project_name}

            if optimization_id:
                if "opik" in metadata_for_opik:
                    metadata_for_opik["opik"]["optimization_id"] = optimization_id

            metadata_for_opik["optimizer_name"] = self.__class__.__name__
            metadata_for_opik["opik_call_type"] = "reasoning" if is_reasoning else "evaluation_llm_task_direct"

            if metadata_for_opik:
                llm_config_params["metadata"] = metadata_for_opik

            # Pass llm_config_params to the Opik monitor
            final_call_params = opik_litellm_monitor.try_add_opik_monitoring_to_params(
                llm_config_params.copy()
            )

            logger.debug(
                f"Calling model '{self.model}' with messages: {messages}, "
                f"final params for litellm (from monitor): {final_call_params}"
            )

            response = litellm.completion(
                model=self.model, messages=messages, **final_call_params
            )
            self.llm_call_counter += 1

            logger.debug(f"Response: {response}")
            return response.choices[0].message.content
        except litellm_exceptions.RateLimitError as e:
            logger.error(f"LiteLLM Rate Limit Error: {e}")
            raise
        except litellm_exceptions.APIConnectionError as e:
            logger.error(f"LiteLLM API Connection Error: {e}")
            raise
        except litellm_exceptions.ContextWindowExceededError as e:
            logger.error(f"LiteLLM Context Window Exceeded Error: {e}")
            raise
        except Exception as e:
            logger.error(f"Error calling model '{self.model}': {type(e).__name__} - {e}")
            raise

    def evaluate_prompt(
        self,
        prompt: chat_prompt.ChatPrompt,
        dataset: opik.Dataset,
        metric: Callable,
        n_samples: Optional[int] = None,
        dataset_item_ids: Optional[List[str]] = None,
        experiment_config: Optional[Dict] = None,
        optimization_id: Optional[str] = None,
        verbose: int = 0,
    ) -> float:
        """
        Evaluate a single prompt (individual) against the dataset.
        
        Args:
            prompt: The prompt to evaluate
            dataset: The dataset to use for evaluation
            metric: Metric function to evaluate on, should have the arguments `dataset_item` and `llm_output`
            n_samples: Optional number of samples to use
            dataset_item_ids: Optional list of dataset item IDs to use
            experiment_config: Optional experiment configuration
            optimization_id: Optional optimization ID
            verbose: Controls internal logging/progress bars (0=off, 1=on).
        
        Returns:
            float: The metric value
        """
        total_items = len(dataset.get_items())
        
        current_experiment_config = experiment_config or {}
        current_experiment_config = {
            **current_experiment_config,
            **{
                "optimizer": self.__class__.__name__,
                "metric": metric.__name__,
                "dataset": dataset.name,
                "configuration": {
                    "prompt": prompt.formatted_messages,
                    "n_samples_for_eval": len(dataset_item_ids) if dataset_item_ids is not None else n_samples,
                    "total_dataset_items": total_items,
                },
            },
        }

        def llm_task(
                dataset_item: Dict[str, Any]
            ) -> Dict[str, str]:
            try:
                messages = [{
                    "role": item["role"],
                    "content": item["content"].format(**dataset_item)
                } for item in prompt.formatted_messages]
            except Exception as e:
                logger.warning(f"Error in llm_task, this is usually a parsing error: {e}")
                return {mappers.EVALUATED_LLM_TASK_OUTPUT: ""}
            
            model_output = self._call_model(
                messages=messages,
                is_reasoning=False
            )
            
            return {mappers.EVALUATED_LLM_TASK_OUTPUT: model_output}
        
        # Evaluate the prompt
        score = task_evaluator.evaluate(
            dataset=dataset,
            dataset_item_ids=dataset_item_ids,
            metric=metric,
            evaluated_task=llm_task,
            num_threads=self.num_threads,
            project_name=self.project_name,
            n_samples=n_samples if dataset_item_ids is None else None,
            experiment_config=current_experiment_config,
            optimization_id=optimization_id,
            verbose=verbose
        )
        return score

    def _llm_deap_crossover(
            self,
            ind1: "creator.Individual",
            ind2: "creator.Individual"
        ) -> Tuple["creator.Individual", "creator.Individual"]:
        """Perform crossover by asking an LLM to blend two parent prompts."""
        reporting.display_message("      Recombining prompts using an LLM.", verbose=self.verbose)

        parent1_messages: List[Dict[Literal["role", "content"], str]] = ind1
        parent2_messages: List[Dict[Literal["role", "content"], str]] = ind2
        current_output_style_guidance = self.output_style_guidance

        user_prompt_for_llm_crossover = f"""Parent Prompt 1:
'''{parent1_messages}'''

Parent Prompt 2:
'''{parent2_messages}'''

Desired output style from target LLM for children prompts: '{current_output_style_guidance}'

Please generate TWO child prompts by intelligently blending the ideas, styles, or structures from these two parents, ensuring the children aim to elicit the desired output style.
Follow the instructions provided in the system prompt regarding the JSON output format:
[
    [{{"role": "<role>", "content": "<content>"}}, {{"role": "<role>", "content": "<content>"}}], #child_1
    [{{"role": "<role>", "content": "<content>"}}, {{"role": "<role>", "content": "<content>"}}], #child_2
]
"""
        try:
            logger.debug(f"Attempting LLM-driven crossover between: '{parent1_messages[:50]}...' and '{parent2_messages[:50]}...' aiming for style: '{current_output_style_guidance[:30]}...'")
            response_content = self._call_model(
                messages=[
                    {"role": "system", "content": self.get_llm_crossover_system_prompt()},
                    {"role": "user", "content": user_prompt_for_llm_crossover},
                ],
                is_reasoning=True 
            )
            logger.debug(f"Raw LLM response for crossover: {response_content}")

            json_response = utils.json_to_dict(response_content)
            if not isinstance(json_response, list) or len(json_response) != 2 or not all(isinstance(cs, list) for cs in json_response):
                logger.warning("LLM Crossover: Malformed or empty children_prompts list. Falling back.")
                raise ValueError("Malformed LLM crossover response")

            child1: List[Dict[Literal["role", "content"], str]] = json_response[0]
            child2: List[Dict[Literal["role", "content"], str]] = json_response[1]
            
            logger.debug(f"LLM Crossover generated child1: {json.dumps(child1)[:50]}... Child2: {json.dumps(child2)[:50]}...")
            return creator.Individual(child1), creator.Individual(child2) 

        except Exception as e:
            logger.warning(f"LLM-driven crossover failed: {e}. Falling back to standard crossover.")
            return self._deap_crossover(ind1, ind2)

    def _get_task_description_for_llm(
            self,
            prompt: chat_prompt.ChatPrompt
        ) -> str:
        """Generates a concise task description for use in LLM prompts for fresh generation or radical innovation."""
        description = "Task: Given a list of AI messages with placeholder values, generate an effective prompt. "
        description += f"The original high-level instruction being optimized is: '{prompt.formatted_messages}'. "
        description += "The goal is to create an effective prompt that guides a language model to perform this task well."
        return description

    def _get_reasoning_system_prompt_for_variation(self) -> str:
        return f"""You are an expert prompt engineer specializing in creating diverse and effective prompts. Given an initial prompt, your task is to generate a diverse set of alternative prompts.

For each prompt variation, consider:
1. Different levels of specificity and detail, including significantly more detailed and longer versions.
2. Various ways to structure the instruction, exploring more complex sentence structures and phrasings.
3. Alternative phrasings that maintain the core intent but vary in style and complexity.
4. Different emphasis on key components, potentially elaborating on them.
5. Various ways to express constraints or requirements.
6. Different approaches to clarity and conciseness, but also explore more verbose and explanatory styles.
7. Alternative ways to guide the model's response format.
8. Consider variations that are substantially longer and more descriptive than the original.

The generated prompts should guide a target LLM to produce outputs in the following style: '{self.output_style_guidance}'

Return a JSON array of prompts with the following structure:
{{
    "prompts": [
        {{
            "prompt": "alternative prompt 1",
            "strategy": "brief description of the variation strategy used, e.g., 'focused on eliciting specific output style'"
        }},
        {{
            "prompt": "alternative prompt 2",
            "strategy": "brief description of the variation strategy used"
        }}
    ]
}}
Each prompt variation should aim to get the target LLM to produce answers matching the desired style: '{self.output_style_guidance}'.
"""

    def get_llm_crossover_system_prompt(self) -> str:
        return f"""You are an expert prompt engineer specializing in creating novel prompts by intelligently blending existing ones. 
Given two parent prompts, your task is to generate one or two new child prompts that effectively combine the strengths, styles, or core ideas of both parents. 
The children should be coherent and aim to explore a potentially more effective region of the prompt design space, with a key goal of eliciting responses from the target language model in the following style: '{self.output_style_guidance}'.

Consider the following when generating children:
- Identify the key instructions, constraints, and desired output formats in each parent, paying attention to any hints about desired output style.
- Explore ways to merge these elements such that the resulting prompt strongly guides the target LLM towards the desired output style.
- You can create a child that is a direct blend, or one that takes a primary structure from one parent and incorporates specific elements from the other, always optimizing for clear instruction towards the desired output style.
- If generating two children, try to make them distinct from each other and from the parents, perhaps by emphasizing different aspects of the parental combination that could lead to the desired output style.

All generated prompts must aim for eliciting answers in the style: '{self.output_style_guidance}'.

Return a JSON object that is a list of both child prompts. Each child prompt is a list of LLM messages. Example:
[
    [{{"role": "<role>", "content": "<content>"}},{{"role": "<role>", "content": "<content>"}}],
    [{{"role": "<role>", "content": "<content>"}},{{"role": "<role>", "content": "<content>"}}]
]


"""

    def _get_radical_innovation_system_prompt(self) -> str:
        return f"""You are an expert prompt engineer and a creative problem solver. 
Given a task description and an existing prompt for that task (which might be underperforming), your goal is to generate a new, significantly improved, and potentially very different prompt. 
Do not just make minor edits. Think about alternative approaches, structures, and phrasings that could lead to better performance. 
Consider clarity, specificity, constraints, and how to best guide the language model for the described task TO PRODUCE OUTPUTS IN THE FOLLOWING STYLE: '{self.output_style_guidance}'.
Return only the new prompt string, with no preamble or explanation.
"""

    def _infer_output_style_from_dataset(
            self,
            dataset: opik.Dataset,
            prompt: chat_prompt.ChatPrompt,
            n_examples: int = 5
        ) -> Optional[str]:
        """Analyzes dataset examples to infer the desired output style."""
        with reporting.infer_output_style(verbose=self.verbose) as report_infer_output_style:
            report_infer_output_style.start_style_inference(n_examples)
            
            try:
                items_to_process = dataset.get_items(n_examples)
            except Exception as e:
                report_infer_output_style.error(f"Failed to get items from dataset '{dataset.name}': {e}")
                return None

            if not items_to_process:
                report_infer_output_style.error(f"Dataset '{dataset.name}' is empty. Cannot infer output style.")
                return None

            # Need at least a couple of examples for meaningful inference
            if len(items_to_process) < min(n_examples, 2):
                report_infer_output_style.error(f"Not enough dataset items (found {len(items_to_process)}) to reliably infer output style. Need at least {min(n_examples,2)}.")
                return None

            examples_str = ""
            for i, item_content in enumerate(items_to_process):
                filtered_content = {x: y for x, y in item_content.items() if x != "id"}
                examples_str += f"Example {i+1}:\nDataset Item:\n{filtered_content}\n---\n"

            user_prompt_for_style_inference = f"""Please analyze the following examples from a dataset and provide a concise, actionable description of the REQUIRED output style for the target LLM. Before describing the output style, make sure to understand the dataset content and structure as it can include input, output and metadata fields. This description will be used to guide other LLMs in generating and refining prompts.

    {examples_str}

    Based on these examples, what is the desired output style description? 
    Remember to focus on aspects like length, tone, structure, content details, and any recurring keywords or phrasing patterns in the outputs. 
    The description should be a single string that can be directly used as an instruction for another LLM.
    Return ONLY this descriptive string.
    """
            #report_infer_output_style.display_style_inference_prompt(user_prompt_for_style_inference)

            try:
                inferred_style = self._call_model(
                    messages=[
                        {"role": "system", "content": self._INFER_STYLE_SYSTEM_PROMPT},
                        {"role": "user", "content": user_prompt_for_style_inference}
                    ],
                    is_reasoning=True
                )
                inferred_style = inferred_style.strip()
                if inferred_style:
                    report_infer_output_style.success(inferred_style)
                    return inferred_style
                else:
                    report_infer_output_style.error("LLM returned empty string for inferred output style.")
                    return None
            except Exception as e:
                report_infer_output_style.error(f"Error during output style inference: {e}")
                return None
