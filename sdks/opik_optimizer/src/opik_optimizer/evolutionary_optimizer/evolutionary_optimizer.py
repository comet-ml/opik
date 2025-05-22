from typing import Optional, Union, List, Dict, Any, Tuple
import opik
import logging
import random
import json
from string import Template
import os
import time
import Levenshtein
import numpy as np

from opik_optimizer.base_optimizer import BaseOptimizer, OptimizationRound
from opik_optimizer.optimization_config.configs import TaskConfig, MetricConfig
from opik_optimizer.optimization_result import OptimizationResult
from opik_optimizer import task_evaluator
from opik_optimizer.optimization_config import mappers
from opik.api_objects import opik_client
from opik.environment import get_tqdm_for_current_environment
from opik_optimizer import _throttle
import litellm
from litellm.caching import Cache
from opik.evaluation.models.litellm import opik_monitor as opik_litellm_monitor

# DEAP imports
from deap import base, creator, tools, algorithms

logger = logging.getLogger(__name__)
tqdm = get_tqdm_for_current_environment()
_rate_limiter = _throttle.get_rate_limiter_for_current_opik_installation()

# Using disk cache for LLM calls
disk_cache_dir = os.path.expanduser("~/.litellm_cache")
litellm.cache = Cache(type="disk", disk_cache_dir=disk_cache_dir)

class EvolutionaryOptimizer(BaseOptimizer):
    """
    Optimizes prompts using a genetic algorithm approach.
    Focuses on evolving the prompt text itself.
    Can operate in single-objective or multi-objective mode.
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
        project_name: Optional[str] = None,
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
        # FIXME: Hack for verbose till its merged
        self.verbose = 1

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
            creator.create("Individual", str, fitness=fitness_attr)

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
        total_distance = 0
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

    def _deap_crossover(
            self,
            ind1: "creator.Individual",
            ind2: "creator.Individual"
        ) -> Tuple["creator.Individual", "creator.Individual"]:
        """Enhanced crossover operation that preserves semantic meaning.
        Attempts chunk-level crossover first, then falls back to word-level.
        """
        str1_orig, str2_orig = str(ind1), str(ind2)

        chunks1 = [chunk.strip() for chunk in str1_orig.split('.') if chunk.strip()]
        chunks2 = [chunk.strip() for chunk in str2_orig.split('.') if chunk.strip()]

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
            
            return creator.Individual(child1_str), creator.Individual(child2_str)

        # Fallback to word-level crossover if chunk-level is not suitable
        words1 = str1_orig.split()
        words2 = str2_orig.split()

        # If either prompt is empty (no words), return parents
        if not words1 or not words2:
            return ind1, ind2

        min_word_len = min(len(words1), len(words2))
        # Need at least 2 words in the shorter prompt for a valid crossover point
        if min_word_len < 2:
            return ind1, ind2

        # Crossover point for words: 1 to min_word_len - 1
        point = random.randint(1, min_word_len - 1)
        child1_words = words1[:point] + words2[point:]
        child2_words = words2[:point] + words1[point:]
        
        return creator.Individual(' '.join(child1_words)), creator.Individual(' '.join(child2_words))

    def _deap_mutation(
            self,
            individual: "creator.Individual",
            task_config: TaskConfig
        ) -> Tuple["creator.Individual",]:
        """Enhanced mutation operation with multiple strategies. Requires task_config for some mutations."""
        prompt = str(individual)
        
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
            return self._word_level_mutation(prompt)
        elif mutation_choice > semantic_threshold:
            # This corresponds to the original 'elif' (structural_mutation)
            return self._structural_mutation(prompt)
        else:
            # This corresponds to the original 'if' (semantic_mutation)
            return self._semantic_mutation(prompt, task_config)

    def _semantic_mutation(
            self,
            prompt: str,
            task_config: TaskConfig
        ) -> Tuple["creator.Individual",]:
        """Enhanced semantic mutation with multiple strategies."""
        current_output_style_guidance = self.output_style_guidance
        if random.random() < 0.1: 
            return self._radical_innovation_mutation(prompt, task_config)
        
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
Task context: {self._get_task_description_for_llm(task_config)}
Desired output style from target LLM: '{current_output_style_guidance}'
Instruction for this modification: {strategy_prompts[strategy]}.
Return only the modified prompt string, nothing else.
"""
            response = self._call_model(
                prompt=user_prompt_for_semantic_mutation, 
                system_prompt=f"You are a prompt engineering expert. Your goal is to modify prompts to improve their effectiveness in eliciting specific types of answers, particularly matching the style: '{current_output_style_guidance}'. Follow the specific modification instruction provided.",
                is_reasoning=True
            )
            return creator.Individual(response.strip()),
        except Exception as e:
            logger.warning(f"Error in semantic mutation for prompt '{prompt[:50]}...': {e}")
            return creator.Individual(prompt),

    def _structural_mutation(
            self,
            prompt: str
        ) -> Tuple["creator.Individual",]:
        """Perform structural mutation (reordering, combining, splitting)."""
        sentences = [s.strip() for s in prompt.split('.') if s.strip()]
        if len(sentences) <= 1:
            return self._word_level_mutation(prompt)
        
        mutation_type = random.random()
        if mutation_type < 0.3:
            # Reorder sentences
            random.shuffle(sentences)
            return creator.Individual('. '.join(sentences) + '.'),
        elif mutation_type < 0.6:
            # Combine adjacent sentences
            if len(sentences) >= 2:
                idx = random.randint(0, len(sentences) - 2)
                combined = sentences[idx] + ' and ' + sentences[idx + 1]
                sentences[idx:idx+2] = [combined]
                return creator.Individual('. '.join(sentences) + '.'),
        else:
            # Split a sentence
            idx = random.randint(0, len(sentences) - 1)
            words = sentences[idx].split()
            if len(words) > 3:
                split_point = random.randint(2, len(words) - 2)
                sentences[idx:idx+1] = [' '.join(words[:split_point]), ' '.join(words[split_point:])]
                return creator.Individual('. '.join(sentences) + '.'),
        
        return creator.Individual(prompt),

    def _word_level_mutation(self, prompt: str) -> Tuple["creator.Individual",]:
        """Perform word-level mutation."""
        words = prompt.split()
        if len(words) <= 1:
            return creator.Individual(prompt),
        
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
        
        return creator.Individual(' '.join(words)),

    def _get_synonym(
            self,
            word: str
        ) -> str:
        """Get a synonym for a word using LLM."""
        try:
            response = self._call_model(
                prompt=f"Give me a single synonym for the word '{word}'. Return only the synonym, nothing else.",
                system_prompt="You are a helpful assistant that provides synonyms. Return only the synonym word, no explanation or additional text.",
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
                prompt=f"Modify this phrase while keeping the same meaning: '{phrase}'. Return only the modified phrase, nothing else.",
                system_prompt="You are a helpful assistant that rephrases text. Return only the modified phrase, no explanation or additional text.",
                is_reasoning=True
            )
            return response.strip()
        except Exception as e:
            logger.warning(f"Error modifying phrase '{phrase}': {e}")
            return phrase

    def _radical_innovation_mutation(
            self,
            prompt_str: str,
            task_config: TaskConfig
        ) -> Tuple["creator.Individual",]:
        """Attempts to generate a significantly improved and potentially very different prompt using an LLM."""
        logger.debug(f"Attempting radical innovation for prompt: {prompt_str[:70]}...")
        task_desc_for_llm = self._get_task_description_for_llm(task_config)
        current_output_style_guidance = self.output_style_guidance
        
        user_prompt_for_radical_innovation = f"""Task Context:
{task_desc_for_llm}
Desired output style from target LLM: '{current_output_style_guidance}'

Existing Prompt (which may be underperforming):
'''{prompt_str}'''

Please generate a new, significantly improved, and potentially very different prompt for this task. 
Focus on alternative approaches, better clarity, or more effective guidance for the language model, aiming for the desired output style.
Return only the new prompt string.
"""
        try:
            new_prompt_str = self._call_model(
                prompt=user_prompt_for_radical_innovation,
                system_prompt=self.get_radical_innovation_system_prompt(),
                is_reasoning=True
            )
            logger.info(f"Radical innovation generated: {new_prompt_str[:70]}... from: {prompt_str[:70]}...")
            return creator.Individual(new_prompt_str.strip()),
        except Exception as e:
            logger.warning(f"Radical innovation mutation failed for prompt '{prompt_str[:50]}...': {e}. Returning original.")
            return creator.Individual(prompt_str),

    def _initialize_population(
        self,
        initial_prompt: str,
        task_config: TaskConfig,
    ) -> List[str]:
        """Initialize the population with diverse variations of the initial prompt, 
           including some 'fresh start' prompts based purely on task description.
           All generated prompts should aim to elicit answers matching self.output_style_guidance.
        """
        population = [initial_prompt]
        if self.population_size <= 1:
            return population

        num_to_generate_total = self.population_size - 1
        num_fresh_starts = max(1, int(num_to_generate_total * 0.2))
        num_variations_on_initial = num_to_generate_total - num_fresh_starts

        task_desc_for_llm = self._get_task_description_for_llm(task_config)
        current_output_style_guidance = self.output_style_guidance

        # Generate "fresh start" prompts if the initial prompt is not performing well
        # Cold start prompts are generated from the task description
        if num_fresh_starts > 0:
            logger.info(f"Generating {num_fresh_starts} 'fresh start' prompts based on task description (aiming for style: '{current_output_style_guidance[:30]}...')...")
            fresh_start_user_prompt = f"""Here is a description of a task:
{task_desc_for_llm}

The goal is to generate prompts that will make a target LLM produce responses in the following style: '{current_output_style_guidance}'.

Please generate {num_fresh_starts} diverse and effective prompt(s) for a language model to accomplish this task, ensuring they guide towards this specific output style.
Focus on clarity, completeness, and guiding the model effectively towards the desired style. Explore different structural approaches.
Your response MUST be a valid JSON list of strings. Do NOT include any other text, explanations, or Markdown formatting like ```json ... ``` around the list.
Example of valid response: ["Prompt targeting specified style.", "Another prompt designed for the output style."]
"""
            try:
                response_content = self._call_model(
                    prompt=fresh_start_user_prompt,
                    system_prompt=f"You are an expert prompt engineer. Your task is to generate novel, effective prompts from scratch based on a task description, specifically aiming for prompts that elicit answers in the style: '{current_output_style_guidance}'. Output ONLY a raw JSON list of strings.",
                    is_reasoning=True
                )
                logger.debug(f"Raw LLM response for fresh start prompts: {response_content}")
                
                cleaned_response_content = response_content.strip()
                if cleaned_response_content.startswith("```json"):
                    cleaned_response_content = cleaned_response_content[7:] 
                    if cleaned_response_content.endswith("```"):
                        cleaned_response_content = cleaned_response_content[:-3]
                elif cleaned_response_content.startswith("```"):
                    cleaned_response_content = cleaned_response_content[3:]
                    if cleaned_response_content.endswith("```"):
                        cleaned_response_content = cleaned_response_content[:-3]
                cleaned_response_content = cleaned_response_content.strip() 

                fresh_prompts = json.loads(cleaned_response_content) 
                if isinstance(fresh_prompts, list) and all(isinstance(p, str) for p in fresh_prompts) and fresh_prompts:
                    population.extend(fresh_prompts[:num_fresh_starts])
                    logger.info(f"Generated {len(fresh_prompts[:num_fresh_starts])} fresh prompts from LLM.")
                else:
                    logger.warning(f"LLM response for fresh starts was not a valid list of strings or was empty: {cleaned_response_content}. Using fallbacks for fresh starts.")
                    population.extend(self._generate_fallback_variations(f"Fresh start targeting style: {current_output_style_guidance[:20]}", num_fresh_starts))
            except json.JSONDecodeError as e_json:
                logger.warning(f"JSONDecodeError generating fresh start prompts: {e_json}. LLM response (after cleaning): '{cleaned_response_content if 'cleaned_response_content' in locals() else response_content}'. Using fallbacks for fresh starts.")
                population.extend(self._generate_fallback_variations(f"Fresh start targeting style: {current_output_style_guidance[:20]}", num_fresh_starts))
            except Exception as e:
                logger.warning(f"Error generating fresh start prompts: {e}. Using fallbacks for fresh starts.")
                population.extend(self._generate_fallback_variations(f"Fresh start targeting style: {current_output_style_guidance[:20]}", num_fresh_starts))

        # Generate variations on the initial prompt for the remaining slots
        # TODO: Could add variations with hyper-parameters from the task config like temperature, etc.
        if num_variations_on_initial > 0:
            logger.info(f"Generating {num_variations_on_initial} variations of the initial prompt (aiming for style: '{current_output_style_guidance[:30]}...')...")
            user_prompt_for_variation = f"""Initial prompt:
'''{initial_prompt}'''

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
            "prompt": "alternative prompt 1 designed for the specified output style",
            "strategy": "brief description of the variation strategy used, e.g., 'direct instruction for target style'"
        }}
        // ... more prompts if num_variations_on_initial > 1
    ]
}}
Ensure a good mix of variations, all targeting the specified output style from the end LLM.
"""
            try:
                response_content_variations = self._call_model(
                    prompt=user_prompt_for_variation,
                    system_prompt=self.get_reasoning_system_prompt_for_variation(), 
                    is_reasoning=True
                )
                logger.debug(f"Raw response for population variations: {response_content_variations}")
                json_response_variations = json.loads(response_content_variations)
                generated_prompts_variations = [p["prompt"] for p in json_response_variations.get("prompts", []) if isinstance(p, dict) and "prompt" in p]
                if generated_prompts_variations:
                    population.extend(generated_prompts_variations[:num_variations_on_initial])
                    logger.info(f"Successfully parsed {len(generated_prompts_variations[:num_variations_on_initial])} variations from LLM response.")
                else:
                    logger.warning("Could not parse 'prompts' list for variations. Using fallback for remaining.")
                    population.extend(self._generate_fallback_variations(initial_prompt, num_variations_on_initial))
            except Exception as e:
                logger.error(f"Error calling LLM for initial population variations: {e}. Using fallback for remaining.")
                population.extend(self._generate_fallback_variations(initial_prompt, num_variations_on_initial))

        # Ensure population is of the required size using unique prompts
        # TODO Test with levenshtein distance
        final_population_set = set()
        final_population_list = []
        for p in population:
            if p not in final_population_set:
                final_population_set.add(p)
                final_population_list.append(p)
        
        # If not enough unique prompts, fill with fallbacks (could be more sophisticated)
        while len(final_population_list) < self.population_size and len(final_population_list) < num_to_generate_total +1:
            fallback_prompt = initial_prompt + f" #fallback{len(final_population_list)}"
            if fallback_prompt not in final_population_set:
                 final_population_list.append(fallback_prompt)
                 final_population_set.add(fallback_prompt)
            else:
                # Safeguard if initial_prompt itself is causing issues with uniqueness
                fallback_prompt = f"Fallback prompt variation {random.randint(1000,9999)}"
                if fallback_prompt not in final_population_set:
                    final_population_list.append(fallback_prompt)
                    final_population_set.add(fallback_prompt)
                # Avoid infinite loop in extreme edge case
                else: break

        logger.info(f"Initialized population with {len(final_population_list)} prompts.")
        # Return exactly population_size prompts if possible, or fewer if generation failed badly.
        return final_population_list[:self.population_size] 

    def _generate_diverse_variation(
            self,
            base_prompt: str,
            seen_prompts: set
        ) -> str:
        """Generate a new variation that's different from existing ones."""
        max_attempts = 5
        for _ in range(max_attempts):
            # Try different mutation strategies
            mutation_choice = random.random()
            if mutation_choice < 0.3:
                new_prompt = self._semantic_mutation(base_prompt)[0]
            elif mutation_choice < 0.6:
                new_prompt = self._structural_mutation(base_prompt)[0]
            else:
                new_prompt = self._word_level_mutation(base_prompt)[0]
            
            # Check if this variation is sufficiently different
            is_diverse = True
            for existing in seen_prompts:
                if Levenshtein.distance(str(new_prompt), existing) / max(len(str(new_prompt)), len(existing)) < 0.3:
                    is_diverse = False
                    break
            if is_diverse:
                return str(new_prompt)
        
        # If we couldn't generate a diverse variation, create a simple one
        return base_prompt + f" #v{len(seen_prompts)}"

    def _generate_fallback_variations(
            self,
            initial_prompt: str,
              num_variations: int
        ) -> List[str]:
        """Generate fallback variations when LLM generation fails."""
        variations = []
        words = initial_prompt.split()
        
        for i in range(num_variations):
            if len(words) > 3:
                # Shuffle words
                shuffled = words.copy()
                random.shuffle(shuffled)
                variations.append(' '.join(shuffled))
            else:
                # Add simple variations
                variations.append(initial_prompt + f" #v{i}")
        
        return variations

    def optimize_prompt(
        self,
        dataset: Union[str, opik.Dataset],
        metric_config: MetricConfig,
        task_config: TaskConfig,
        experiment_config: Optional[Dict] = None,
        n_samples: Optional[int] = None,
        auto_continue: bool = False,
        **kwargs,
    ) -> OptimizationResult:
        self.llm_call_counter = 0
        self._history = []
        self._current_optimization_id = None
        self._current_generation = 0
        self._best_fitness_history = []
        self._generations_without_improvement = 0
        self._llm_cache.clear()
        self._current_population = []
        self._generations_without_overall_improvement = 0

        # Determine final output_style_guidance
        effective_output_style_guidance = self.output_style_guidance
        if self.infer_output_style and \
           (self.output_style_guidance is None or self.output_style_guidance == self.DEFAULT_OUTPUT_STYLE_GUIDANCE):
            # If user wants inference AND hasn't provided a specific custom guidance
            inferred_style = self._infer_output_style_from_dataset(dataset, task_config)
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
        
        # The methods like get_reasoning_system_prompt_for_variation will now use the potentially updated self.output_style_guidance
        log_prefix = "DEAP MOO" if self.enable_moo else "DEAP SO"
        logger.info(f"Starting {log_prefix} Evolutionary Optimization for prompt: {task_config.instruction_prompt[:100]}...")
        logger.info(f"Population: {self.population_size}, Generations: {self.num_generations}, Mutation: {self.mutation_rate}, Crossover: {self.crossover_rate}")

        opik_dataset_obj: opik.Dataset
        if isinstance(dataset, str):
            opik_dataset_obj = self._opik_client.get_dataset(dataset)
        else:
            opik_dataset_obj = dataset

        opik_optimization_run = None
        try:
            opik_optimization_run = self._opik_client.create_optimization(
                dataset_name=opik_dataset_obj.name,
                objective_name=metric_config.metric.name,
                metadata={"optimizer": self.__class__.__name__},
            )
            self._current_optimization_id = opik_optimization_run.id
            logger.info(f"Created Opik Optimization run with ID: {self._current_optimization_id}")
        except Exception as e:
            logger.warning(f"Opik server error: {e}. Continuing without Opik tracking.")

        # Use of multi-objective fitness function or single-objective fitness function
        if self.enable_moo:
            def _deap_evaluate_individual_fitness(
                    individual_prompt_str: str
                ) -> Tuple[float, float]:
                primary_fitness_score = self.evaluate_prompt(
                    dataset=opik_dataset_obj, metric_config=metric_config, task_config=task_config,
                    prompt=str(individual_prompt_str), n_samples=n_samples,
                    experiment_config=(experiment_config or {}).copy(),
                    optimization_id=self._current_optimization_id, verbose=0
                )
                prompt_length = float(len(str(individual_prompt_str)))
                logger.debug(f"Evaluated MOO individual '{str(individual_prompt_str)[:50]}...' -> Primary Score: {primary_fitness_score:.4f}, Length: {prompt_length}")
                return (primary_fitness_score, prompt_length)
        else:
            # Single-objective
            def _deap_evaluate_individual_fitness(
                    individual_prompt_str: str
                ) -> Tuple[float,]:
                fitness_score = self.evaluate_prompt(
                    dataset=opik_dataset_obj, metric_config=metric_config, task_config=task_config,
                    prompt=str(individual_prompt_str), n_samples=n_samples,
                    experiment_config=(experiment_config or {}).copy(),
                    optimization_id=self._current_optimization_id, verbose=0
                )
                logger.debug(f"Evaluated SO individual '{str(individual_prompt_str)[:50]}...' -> Score: {fitness_score:.4f}")
                return (fitness_score,)

        # Register the fitness function with DEAP
        self.toolbox.register("evaluate", _deap_evaluate_individual_fitness)

        initial_prompt_strings = self._initialize_population(
            initial_prompt=task_config.instruction_prompt, task_config=task_config
        )
        deap_population = [creator.Individual(p_str) for p_str in initial_prompt_strings]
        deap_population = deap_population[:self.population_size]

        initial_eval_result = _deap_evaluate_individual_fitness(task_config.instruction_prompt)
        initial_primary_score = initial_eval_result[0]
        initial_length = initial_eval_result[1] if self.enable_moo else float(len(task_config.instruction_prompt))
        
        best_primary_score_overall = initial_primary_score
        best_prompt_overall = task_config.instruction_prompt
        if self.enable_moo:
            logger.info(f"Initial prompt '{task_config.instruction_prompt[:100]}...' -> Primary Score: {initial_primary_score:.4f}, Length: {initial_length}")
        else:
            logger.info(f"Initial prompt '{task_config.instruction_prompt[:100]}...' score: {initial_primary_score:.4f}")

        # Initialize the hall of fame (Pareto front for MOO) and stats for MOO or SO
        if self.enable_moo:
            hof = tools.ParetoFront()
            stats_primary = tools.Statistics(lambda ind: ind.fitness.values[0])
            stats_length = tools.Statistics(lambda ind: ind.fitness.values[1])
            stats_primary.register("avg_score", lambda x: sum(x) / len(x) if len(x) > 0 else 0)
            stats_primary.register("max_score", max)
            stats_length.register("avg_len", lambda x: sum(x) / len(x) if len(x) > 0 else 0)
            stats_length.register("min_len", min)
            mstats = tools.MultiStatistics(score=stats_primary, length=stats_length)
            logbook_header_stats = mstats.fields
        else:
            # Single-objective
            hof = tools.HallOfFame(self.DEFAULT_HALL_OF_FAME_SIZE)
            stats = tools.Statistics(lambda ind: ind.fitness.values[0])
            stats.register("avg", lambda x: sum(x) / len(x) if len(x) > 0 else 0)
            stats.register("std", lambda x: (sum((xi - (sum(x) / len(x) if len(x) > 0 else 0))**2 for xi in x) / len(x))**0.5 if len(x) > 1 else 0)
            stats.register("min", min)
            stats.register("max", max)
            logbook_header_stats = stats.fields
        
        logbook = tools.Logbook()
        logbook.header = ["gen", "evals"] + logbook_header_stats

        # Evaluate the initial population
        fitnesses = list(map(self.toolbox.evaluate, deap_population))
        for ind, fit in zip(deap_population, fitnesses):
            ind.fitness.values = fit
        
        hof.update(deap_population)
        record_stats = mstats if self.enable_moo else stats
        record = record_stats.compile(deap_population) if record_stats else {}
        logbook.record(gen=0, evals=len(deap_population), **record)
        if self.verbose >= 1:
            print(logbook.stream)

        if hof and len(hof) > 0:
            if self.enable_moo:
                current_best_for_primary = max(hof, key=lambda ind: ind.fitness.values[0])
                best_primary_score_overall = current_best_for_primary.fitness.values[0]
                best_prompt_overall = str(current_best_for_primary)
            else:
                # Single-objective
                current_best_on_front = hof[0]
                best_primary_score_overall = current_best_on_front.fitness.values[0]
            
            if self.enable_moo:
                logger.info(f"Gen {0}: New best primary score: {best_primary_score_overall:.4f}, Prompt: {best_prompt_overall[:100]}...")
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
            ).dict()
            self._add_to_history(initial_round_data)

        pbar_desc = f"{log_prefix} Evolutionary Optimization"
        pbar_postfix_key = "best_primary_score" if self.enable_moo else "best_score"
        pbar = tqdm(
            total=self.num_generations,
            desc=pbar_desc,
            unit="gen",
            disable=self.verbose < 1,
            postfix={pbar_postfix_key: f"{best_primary_score_overall:.4f}", "llm_calls": self.llm_call_counter}
        )

        gen = 0
        for gen_idx in range(1, self.num_generations + 1):
            gen = gen_idx
            self._current_generation = gen
            pbar.set_postfix({pbar_postfix_key: f"{best_primary_score_overall:.4f}", "llm_calls": self.llm_call_counter})
            previous_best_primary_score_for_gen = best_primary_score_overall

            # Population restart logic
            current_pop_best_primary = 0.0
            if deap_population and deap_population[0].fitness.valid:
                current_pop_best_primary = max(ind.fitness.values[0] for ind in deap_population if ind.fitness.valid)
            
            if self._best_fitness_history and current_pop_best_primary < self._best_fitness_history[-1] * (1 + self.DEFAULT_RESTART_THRESHOLD):
                 self._generations_without_improvement += 1
            else:
                 self._generations_without_improvement = 0
            self._best_fitness_history.append(current_pop_best_primary)

            if self._generations_without_improvement >= self.DEFAULT_RESTART_GENERATIONS:
                logger.info(f"Detected stagnation in primary objective at gen {gen}. Restarting population...")
                elites_for_restart = list(hof) if self.enable_moo else list(tools.selBest(deap_population, self.elitism_size))
                seed_prompt_for_restart = str(max(elites_for_restart, key=lambda ind: ind.fitness.values[0])) if elites_for_restart else best_prompt_overall
                
                new_population_strings = self._initialize_population(initial_prompt=seed_prompt_for_restart, task_config=task_config)
                deap_population = [creator.Individual(p_str) for p_str in new_population_strings]
                self._generations_without_improvement = 0
                fitnesses_new = list(map(self.toolbox.evaluate, deap_population))
                for ind, fit in zip(deap_population, fitnesses_new):
                    ind.fitness.values = fit
                # Offspring will be selected from this new population in the next step

            # Standard DEAP evolutionary algorithm steps
            if self.enable_moo:
                # NSGA-II is used for MOO
                offspring = self.toolbox.select(deap_population, self.population_size)
            else:
                # Single-objective: Elitism + Selection
                elites = tools.selBest(deap_population, self.elitism_size)
                selected_offspring = self.toolbox.select(deap_population, len(deap_population) - self.elitism_size)
                offspring = elites + selected_offspring
            
            # Set up the offspring for the next generation
            offspring = list(map(self.toolbox.clone, offspring))
            for child1, child2 in zip(offspring[::2], offspring[1::2]):
                if random.random() < self.crossover_rate:
                    self.toolbox.mate(child1, child2)
                    del child1.fitness.values
                    del child2.fitness.values

            # Mutate the offspring
            current_mutation_rate = self._get_adaptive_mutation_rate()
            for mutant in offspring:
                if random.random() < current_mutation_rate:
                    self.toolbox.mutate(mutant, task_config=task_config)
                    del mutant.fitness.values
            
            # Evaluate the offspring
            invalid_ind = [ind for ind in offspring if not ind.fitness.valid]
            fitnesses_eval = map(self.toolbox.evaluate, invalid_ind)
            for ind, fit in zip(invalid_ind, fitnesses_eval):
                ind.fitness.values = fit
            
            # Update the hall of fame
            hof.update(offspring)
            deap_population[:] = offspring # Replace population

            # Update overall best score and prompt (based on primary objective for consistency)
            if hof and len(hof) > 0:
                if self.enable_moo:
                    current_best_on_front = max(hof, key=lambda ind: ind.fitness.values[0])
                    updated_best_primary_score = current_best_on_front.fitness.values[0]
                else:
                    # Single-objective
                    current_best_on_front = hof[0]
                    updated_best_primary_score = current_best_on_front.fitness.values[0]
                
                if updated_best_primary_score > best_primary_score_overall:
                    best_primary_score_overall = updated_best_primary_score
                    best_prompt_overall = str(current_best_on_front)
                    logger.info(f"Gen {gen}: New best primary score: {best_primary_score_overall:.4f}, Prompt: {best_prompt_overall[:100]}...")
                    self._generations_without_overall_improvement = 0
                elif updated_best_primary_score == previous_best_primary_score_for_gen:
                    # Check against score at start of this gen's logic
                    self._generations_without_overall_improvement += 1
                else:
                    # Score might have decreased or HOF is empty (less likely for SO HOF with size > 0)
                    self._generations_without_overall_improvement += 1
            else:
                # Score might have decreased or HOF is empty (less likely for SO HOF with size > 0)
                self._generations_without_overall_improvement += 1
            
            record = record_stats.compile(deap_population) if record_stats else {}
            logbook.record(gen=gen, evals=len(invalid_ind), **record)
            if self.verbose >= 1:
                print(logbook.stream)

            # History logging for this transition
            # FIXME: Use model.dump() instead of dict()
            gen_round_data = OptimizationRound(
                round_number=gen,
                current_prompt=best_prompt_overall, # Representative best
                current_score=best_primary_score_overall,
                generated_prompts=[{"prompt": str(ind), "score": ind.fitness.values[0]} for ind in deap_population if ind.fitness.valid],
                best_prompt=best_prompt_overall,
                best_score=best_primary_score_overall,
                improvement=(best_primary_score_overall - initial_primary_score) / abs(initial_primary_score) if initial_primary_score and initial_primary_score != 0 else (1.0 if best_primary_score_overall > 0 else 0.0)
            ).dict()
            self._add_to_history(gen_round_data)
            pbar.update(1)

            if self._generations_without_overall_improvement >= self.DEFAULT_EARLY_STOPPING_GENERATIONS:
                logger.info(f"Overall best score has not improved for {self.DEFAULT_EARLY_STOPPING_GENERATIONS} generations. Stopping early at gen {gen}.")
                break

        pbar.close()
        logger.info(f"\n{log_prefix} Evolutionary Optimization finished after {gen} generations.")
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
                final_best_prompt = str(best_overall_solution)
                final_primary_score = best_overall_solution.fitness.values[0]
                final_length = best_overall_solution.fitness.values[1]
                logger.info(final_results_log)
                logger.info(f"Representative best prompt (highest primary score from Pareto front): '{final_best_prompt}'")
                logger.info(f"  Primary Score ({metric_config.metric.name}): {final_primary_score:.4f}")
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
                final_length = float(len(final_best_prompt))
                final_details.update({"initial_primary_score": initial_primary_score, "initial_length": initial_length, 
                                      "final_prompt_representative": final_best_prompt, "final_primary_score_representative": final_primary_score, 
                                      "final_length_representative": final_length, "pareto_front_solutions": []})
        else:
            # Single-objective
            final_best_prompt = best_prompt_overall
            final_primary_score = best_primary_score_overall
            logger.info(f"Final best prompt from Hall of Fame: '{final_best_prompt}'")
            logger.info(f"Final best score ({metric_config.metric.name}): {final_primary_score:.4f}")
            final_details.update({
                "initial_prompt": task_config.instruction_prompt,
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
            "total_generations_run": gen,
            "population_size": self.population_size,
            "mutation_probability": self.mutation_rate,
            "crossover_probability": self.crossover_rate,
            "elitism_size": self.elitism_size if not self.enable_moo else "N/A (MOO uses NSGA-II)",
            "adaptive_mutation": self.adaptive_mutation,
            "deap_logbook": logbook.stream if logbook else "Not available",
            "task_config": task_config.dict(),
            "metric_config": metric_config.dict(),
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
        return OptimizationResult(
            optimizer=self.__class__.__name__,
            prompt=final_best_prompt, 
            score=final_primary_score, 
            metric_name=metric_config.metric.name,
            details=final_details,
            history=self.get_history(),
            llm_calls=self.llm_call_counter
        )

    @_throttle.rate_limited(_rate_limiter)
    def _call_model(
        self,
        prompt: str,
        system_prompt: Optional[str] = None,
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

            messages = []
            if system_prompt:
                messages.append({"role": "system", "content": system_prompt})
            messages.append({"role": "user", "content": prompt})

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
            return response.choices[0].message.content
        except litellm.exceptions.RateLimitError as e:
            logger.error(f"LiteLLM Rate Limit Error: {e}")
            raise
        except litellm.exceptions.APIConnectionError as e:
            logger.error(f"LiteLLM API Connection Error: {e}")
            raise
        except litellm.exceptions.ContextWindowExceededError as e:
            logger.error(f"LiteLLM Context Window Exceeded Error: {e}")
            raise
        except Exception as e:
            logger.error(f"Error calling model '{self.model}': {type(e).__name__} - {e}")
            raise

    def evaluate_prompt(
        self,
        dataset: Union[str, opik.Dataset],
        metric_config: MetricConfig,
        task_config: TaskConfig,
        prompt: str,
        n_samples: Optional[int] = None,
        dataset_item_ids: Optional[List[str]] = None,
        experiment_config: Optional[Dict] = None,
        optimization_id: Optional[str] = None,
        verbose: int = 0,
    ) -> float:
        """
        Evaluate a single prompt (individual) against the dataset.
        Adapted from MetaPromptOptimizer._evaluate_prompt.
        """
        effective_verbose = self.verbose if verbose == 0 else verbose

        if isinstance(dataset, str):
            # This should ideally be done once in optimize_prompt if dataset is a string
            # but if called standalone, we need to handle it.
            # TODO Move to base class
            opik_eval_dataset = self._opik_client.get_dataset(dataset)
        else:
            opik_eval_dataset = dataset

        total_items = len(opik_eval_dataset.get_items())
        
        # Determine subset_size for this evaluation run
        # TODO Move to dataset utils
        if dataset_item_ids:
            subset_size = len(dataset_item_ids)
            logger.debug(f"Using provided {subset_size} dataset_item_ids for evaluation.")
        elif n_samples is not None:
            if n_samples > total_items:
                logger.warning(
                    f"Requested n_samples ({n_samples}) for individual evaluation is larger than dataset size ({total_items}). Using full dataset."
                )
                subset_size = None
            elif n_samples <= 0:
                logger.warning(
                    f"Requested n_samples ({n_samples}) is <=0. Using full dataset for this evaluation."
                )
                subset_size = None
            else:
                subset_size = n_samples
                logger.debug(f"Using specified n_samples: {subset_size} items for this evaluation run.")
        else:
            # Default behavior if no n_samples and no dataset_item_ids are given for this specific call
            # This case should be rare if n_samples is passed down from optimize_prompt
            subset_size = min(total_items, min(20, max(10, int(total_items * 0.2))))
            logger.debug(
                f"Using automatic subset size for this evaluation: {subset_size} items (20% of {total_items} total items)"
            )

        current_experiment_config = experiment_config or {}
        current_experiment_config = {
            **current_experiment_config,
            **{
                "optimizer": self.__class__.__name__,
                "metric": metric_config.metric.name,
                "dataset": opik_eval_dataset.name,
                "configuration": {
                    "prompt_evaluated": prompt,
                    "n_samples_for_eval": subset_size if dataset_item_ids is None else len(dataset_item_ids),
                    "total_dataset_items": total_items,
                },
            },
        }

        def llm_task(
                dataset_item: Dict[str, Any]
            ) -> Dict[str, str]:
            if hasattr(dataset_item, "to_dict"):
                dataset_item = dataset_item.to_dict()

            for input_key in task_config.input_dataset_fields:
                if input_key not in dataset_item:
                    raise ValueError(f"Input field '{input_key}' not found in dataset sample: {dataset_item}")
            if task_config.output_dataset_field not in dataset_item:
                raise ValueError(f"Output field '{task_config.output_dataset_field}' not found in dataset sample: {dataset_item}")

            prompt_for_llm: str
            field_mapping = {
                field: dataset_item[field]
                for field in task_config.input_dataset_fields
                if field in dataset_item
            }

            if getattr(task_config, "use_chat_prompt", False):
                candidate_template = Template(prompt)
                user_content_parts = []
                for field_name in task_config.input_dataset_fields:
                    if field_name in dataset_item:
                        user_content_parts.append(f"{field_name.capitalize()}: {dataset_item[field_name]}")
                user_content = "\n".join(user_content_parts)
                
                raw_model_output = self._call_model(
                    prompt=user_content,
                    system_prompt=prompt,
                    is_reasoning=False
                )

            else:
                input_clauses = []
                for field_name in task_config.input_dataset_fields:
                    if field_name in dataset_item:
                        input_clauses.append(
                            f"{field_name.capitalize()}: {dataset_item[field_name]}"
                        )
                item_specific_inputs_str = "\n".join(input_clauses)
                prompt_for_llm = f"{prompt}\n\n{item_specific_inputs_str}"
                
                raw_model_output = self._call_model(
                    prompt=prompt_for_llm,
                    system_prompt=None,
                    is_reasoning=False
                )
            
            cleaned_model_output = raw_model_output.strip()
            output_field = task_config.output_dataset_field
            prefixes_to_strip = [f"{output_field.capitalize()}:", f"{output_field}:", "Answer:"]
            for prefix in prefixes_to_strip:
                if cleaned_model_output.lower().startswith(prefix.lower()):
                    cleaned_model_output = cleaned_model_output[len(prefix):].strip()
                    break
            
            return {mappers.EVALUATED_LLM_TASK_OUTPUT: cleaned_model_output}

        logger.debug(
            f"Starting evaluation for a prompt with {subset_size if subset_size else 'all'} samples (or specific IDs) for metric: {metric_config.metric.name}"
        )
        
        # Evaluate the prompt
        score = task_evaluator.evaluate(
            dataset=opik_eval_dataset,
            dataset_item_ids=dataset_item_ids,
            metric_config=metric_config,
            evaluated_task=llm_task,
            num_threads=self.num_threads,
            project_name=self.project_name,
            n_samples=subset_size if dataset_item_ids is None else None,
            experiment_config=current_experiment_config,
            optimization_id=optimization_id,
            # FIXME: Hack for verbose till its merged
            #verbose=effective_verbose,
        )
        logger.debug(f"Evaluation score for prompt: {score:.4f}")
        return score

    def _llm_deap_crossover(
            self,
            ind1: "creator.Individual",
            ind2: "creator.Individual"
        ) -> Tuple["creator.Individual", "creator.Individual"]:
        """Perform crossover by asking an LLM to blend two parent prompts."""
        parent1_str = str(ind1)
        parent2_str = str(ind2)
        current_output_style_guidance = self.output_style_guidance

        user_prompt_for_llm_crossover = f"""Parent Prompt 1:
'''{parent1_str}'''

Parent Prompt 2:
'''{parent2_str}'''

Desired output style from target LLM for children prompts: '{current_output_style_guidance}'

Please generate one or two child prompts by intelligently blending the ideas, styles, or structures from these two parents, ensuring the children aim to elicit the desired output style.
Follow the instructions provided in the system prompt regarding the JSON output format ({{"children_prompts": ["child1", ... ]}}).
"""
        try:
            logger.debug(f"Attempting LLM-driven crossover between: '{parent1_str[:50]}...' and '{parent2_str[:50]}...' aiming for style: '{current_output_style_guidance[:30]}...'")
            response_content = self._call_model(
                prompt=user_prompt_for_llm_crossover,
                system_prompt=self.get_llm_crossover_system_prompt(), 
                is_reasoning=True 
            )
            logger.debug(f"Raw LLM response for crossover: {response_content}")

            json_response = json.loads(response_content)
            children_strings = json_response.get("children_prompts", [])

            if not children_strings or not isinstance(children_strings, list) or not all(isinstance(cs, str) for cs in children_strings):
                logger.warning("LLM Crossover: Malformed or empty children_prompts list. Falling back.")
                raise ValueError("Malformed LLM crossover response")

            child1_str = children_strings[0]
            child2_str = children_strings[1] if len(children_strings) > 1 else self._deap_mutation(creator.Individual(parent2_str), task_config=None)[0] # task_config might not be available or needed here for simple mutation
            
            logger.debug(f"LLM Crossover generated child1: {child1_str[:50]}... Child2: {child2_str[:50]}...")
            return creator.Individual(child1_str), creator.Individual(str(child2_str)) 

        except Exception as e:
            logger.warning(f"LLM-driven crossover failed: {e}. Falling back to standard crossover.")
            return self._deap_crossover(ind1, ind2)

    def _get_task_description_for_llm(
            self,
            task_config: TaskConfig
        ) -> str:
        """Generates a concise task description for use in LLM prompts for fresh generation or radical innovation."""
        input_fields_str = ", ".join(task_config.input_dataset_fields)
        output_field_str = task_config.output_dataset_field
        description = f"Task: Given input(s) from field(s) '{input_fields_str}', generate a response for the field '{output_field_str}'. "
        description += f"The original high-level instruction being optimized is: '{task_config.instruction_prompt}'. "
        description += "The goal is to create an effective prompt that guides a language model to perform this task well."
        return description

    def get_reasoning_system_prompt_for_variation(self) -> str:
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

Return a JSON object with a single key "children_prompts", which is a list of strings. Each string is a child prompt.
Example for one child: {{"children_prompts": ["child prompt 1 designed for specified style"]}}
Example for two children: {{"children_prompts": ["child prompt 1 for target style", "child prompt 2 also for target style"]}}
Generate at least one child, and at most two. All generated prompts must aim for eliciting answers in the style: '{self.output_style_guidance}'.
"""

    def get_radical_innovation_system_prompt(self) -> str:
        return f"""You are an expert prompt engineer and a creative problem solver. 
Given a task description and an existing prompt for that task (which might be underperforming), your goal is to generate a new, significantly improved, and potentially very different prompt. 
Do not just make minor edits. Think about alternative approaches, structures, and phrasings that could lead to better performance. 
Consider clarity, specificity, constraints, and how to best guide the language model for the described task TO PRODUCE OUTPUTS IN THE FOLLOWING STYLE: '{self.output_style_guidance}'.
Return only the new prompt string, with no preamble or explanation.
"""

    def _infer_output_style_from_dataset(
            self,
            dataset: opik.Dataset,
            task_config: TaskConfig,
            n_examples: int = 5
        ) -> Optional[str]:
        """Analyzes dataset examples to infer the desired output style."""
        logger.info(f"Attempting to infer output style from up to {n_examples} dataset examples...")
        try:
            all_items = dataset.get_items()
        except Exception as e:
            logger.error(f"Failed to get items from dataset '{dataset.name}': {e}")
            return None

        if not all_items:
            logger.warning(f"Dataset '{dataset.name}' is empty. Cannot infer output style.")
            return None

        # Take the first n_examples
        items_to_process = all_items[:n_examples]

        # Need at least a couple of examples for meaningful inference
        if len(items_to_process) < min(n_examples, 2):
            logger.warning(f"Not enough dataset items (found {len(items_to_process)}) to reliably infer output style. Need at least {min(n_examples,2)}.")
            return None

        examples_str = ""
        for i, item_obj in enumerate(items_to_process):
            item_content = item_obj.content if hasattr(item_obj, 'content') else item_obj
            if not isinstance(item_content, dict):
                logger.warning(f"Dataset item {i} does not have a .content dictionary or is not a dict itself. Skipping item: {item_obj}")
                continue

            input_parts = []
            for field in task_config.input_dataset_fields:
                if field in item_content:
                    input_parts.append(f"{field.capitalize()}: {item_content[field]}")
            input_str = "\n".join(input_parts)
            output_str = item_content.get(task_config.output_dataset_field, "[NO OUTPUT FIELD FOUND]")
            examples_str += f"Example {i+1}:\nInput(s):\n{input_str}\nOutput: {output_str}\n---\n"

        user_prompt_for_style_inference = f"""Please analyze the following input-output examples from a dataset and provide a concise, actionable description of the REQUIRED output style for the target LLM. This description will be used to guide other LLMs in generating and refining prompts.

{examples_str}

Based on these examples, what is the desired output style description? 
Remember to focus on aspects like length, tone, structure, content details, and any recurring keywords or phrasing patterns in the outputs. 
The description should be a single string that can be directly used as an instruction for another LLM.
Return ONLY this descriptive string.
"""
        try:
            inferred_style = self._call_model(
                prompt=user_prompt_for_style_inference,
                system_prompt=self._INFER_STYLE_SYSTEM_PROMPT,
                is_reasoning=True
            )
            inferred_style = inferred_style.strip()
            if inferred_style:
                logger.info(f"Inferred output style: '{inferred_style}'")
                return inferred_style
            else:
                logger.warning("LLM returned empty string for inferred output style.")
                return None
        except Exception as e:
            logger.error(f"Error during output style inference: {e}")
            return None
