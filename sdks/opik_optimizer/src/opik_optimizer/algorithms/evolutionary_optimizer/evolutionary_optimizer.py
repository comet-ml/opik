import copy
import json
import logging
import random
from typing import Any, cast
import sys
import warnings

import numpy as np

# DEAP imports
from deap import base, tools
from deap import creator as _creator

from opik_optimizer.base_optimizer import (
    AlgorithmResult,
    BaseOptimizer,
    OptimizationContext,
)
from opik_optimizer.utils.prompt_library import PromptOverrides
from ...api_objects import chat_prompt

from . import helpers, reporting
from . import prompts as evo_prompts
from .ops import crossover_ops, mutation_ops, style_ops, population_ops

logger = logging.getLogger(__name__)
creator = cast(Any, _creator)  # type: ignore[assignment]

DEFAULT_DIVERSITY_THRESHOLD = 0.7


class EvolutionaryOptimizer(BaseOptimizer):
    """
    Evolutionary Optimizer that uses genetic algorithms to evolve and improve prompts over generations.

    This optimizer uses a 4-stage genetic algorithm approach:

    1. Generate candidate prompts through variations of the best prompts (exploitation) and
       completely new prompts (exploration)
    2. Evaluate the candidate prompts on the dataset
    3. Select the best prompts based on fitness
    4. Repeat until convergence or max generations reached

    This algorithm is best used if you have a first draft prompt and would like to find a better
    prompt through iterative evolution. It supports both single-objective and multi-objective
    optimization (balancing performance and prompt length).

    Note: This algorithm is time consuming and can be expensive to run.

    Args:
        model: LiteLLM model name for optimizer's internal operations (mutations, crossover, etc.)
        model_parameters: Optional dict of LiteLLM parameters for optimizer's internal LLM calls.
            Common params: temperature, max_tokens, max_completion_tokens, top_p.
            See: https://docs.litellm.ai/docs/completion/input
        population_size: Number of prompts in the population
        num_generations: Number of generations to run
        mutation_rate: Mutation rate for genetic operations
        crossover_rate: Crossover rate for genetic operations
        tournament_size: Tournament size for selection
        elitism_size: Number of elite prompts to preserve across generations
        adaptive_mutation: Whether to use adaptive mutation that adjusts based on population diversity
        enable_moo: Whether to enable multi-objective optimization (optimizes metric and prompt length)
        enable_llm_crossover: Whether to enable LLM-based crossover operations
        output_style_guidance: Optional guidance for output style in generated prompts
        infer_output_style: Whether to automatically infer output style from the dataset
        n_threads: Number of threads for parallel evaluation
        verbose: Controls internal logging/progress bars (0=off, 1=on)
        seed: Random seed for reproducibility
        prompt_overrides: Optional dict or callable to customize internal prompts.
            Dict: {"prompt_key": "new_template"} to override specific prompts.
            Callable: function(prompts: PromptLibrary) -> None to modify prompts programmatically.
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
    DEFAULT_RESTART_THRESHOLD = 0.01
    DEFAULT_RESTART_GENERATIONS = 3
    DEFAULT_EARLY_STOPPING_GENERATIONS = 5
    DEFAULT_ENABLE_MOO = True
    DEFAULT_ENABLE_LLM_CROSSOVER = True
    DEFAULT_SEED = 42
    DEFAULT_OUTPUT_STYLE_GUIDANCE = (
        "Produce clear, effective, and high-quality responses suitable for the task."
    )
    DEFAULT_MOO_WEIGHTS = (1.0, -1.0)  # (Maximize Score, Minimize Length)

    # Prompt templates for this optimizer
    # Keys match what ops files expect (e.g., prompts.get("infer_style_system_prompt"))
    DEFAULT_PROMPTS: dict[str, str] = {
        "infer_style_system_prompt": evo_prompts.INFER_STYLE_SYSTEM_PROMPT,
        "style_inference_user_prompt_template": evo_prompts.STYLE_INFERENCE_USER_PROMPT_TEMPLATE,
        "semantic_mutation_system_prompt_template": evo_prompts.SEMANTIC_MUTATION_SYSTEM_PROMPT_TEMPLATE,
        "semantic_mutation_user_prompt_template": evo_prompts.SEMANTIC_MUTATION_USER_PROMPT_TEMPLATE,
        "synonyms_system_prompt": evo_prompts.SYNONYMS_SYSTEM_PROMPT,
        "rephrase_system_prompt": evo_prompts.REPHRASE_SYSTEM_PROMPT,
        "fresh_start_system_prompt_template": evo_prompts.FRESH_START_SYSTEM_PROMPT_TEMPLATE,
        "fresh_start_user_prompt_template": evo_prompts.FRESH_START_USER_PROMPT_TEMPLATE,
        "variation_system_prompt_template": evo_prompts.VARIATION_SYSTEM_PROMPT_TEMPLATE,
        "variation_user_prompt_template": evo_prompts.VARIATION_USER_PROMPT_TEMPLATE,
        "llm_crossover_system_prompt_template": evo_prompts.LLM_CROSSOVER_SYSTEM_PROMPT_TEMPLATE,
        "llm_crossover_user_prompt_template": evo_prompts.LLM_CROSSOVER_USER_PROMPT_TEMPLATE,
        "radical_innovation_system_prompt_template": evo_prompts.RADICAL_INNOVATION_SYSTEM_PROMPT_TEMPLATE,
        "radical_innovation_user_prompt_template": evo_prompts.RADICAL_INNOVATION_USER_PROMPT_TEMPLATE,
        "mutation_strategy_rephrase": evo_prompts.MUTATION_STRATEGY_REPHRASE,
        "mutation_strategy_simplify": evo_prompts.MUTATION_STRATEGY_SIMPLIFY,
        "mutation_strategy_elaborate": evo_prompts.MUTATION_STRATEGY_ELABORATE,
        "mutation_strategy_restructure": evo_prompts.MUTATION_STRATEGY_RESTRUCTURE,
        "mutation_strategy_focus": evo_prompts.MUTATION_STRATEGY_FOCUS,
        "mutation_strategy_increase_complexity_and_detail": evo_prompts.MUTATION_STRATEGY_INCREASE_COMPLEXITY,
    }

    def pre_optimization(self, context: OptimizationContext) -> None:
        """Store agent reference for use in evaluation."""
        self.agent = context.agent

    def get_config(self, context: OptimizationContext) -> dict[str, Any]:
        """Return optimizer-specific configuration for display."""
        return {
            "optimizer": f"Evolutionary Optimization ({'DEAP MOO' if self.enable_moo else 'DEAP SO'})",
            "population_size": self.population_size,
            "generations": self.num_generations,
            "mutation_rate": self.mutation_rate,
            "crossover_rate": self.crossover_rate,
        }

    def get_metadata(self, context: OptimizationContext) -> dict[str, Any]:
        """
        Return Evolutionary-specific metadata for the optimization result.

        Provides algorithm-specific configuration. Trial counts come from context.
        """
        return {
            "population_size": self.population_size,
            "num_generations": self.num_generations,
            "mutation_rate": self.mutation_rate,
            "crossover_rate": self.crossover_rate,
            "enable_moo": self.enable_moo,
        }

    def _finalize_finish_reason(self, context: OptimizationContext) -> None:
        """
        Set finish_reason with evolutionary-specific stagnation detection.

        Adds "no_improvement" finish reason when generations without improvement
        exceeds the early stopping threshold.
        """
        if context.finish_reason is None:
            if context.trials_completed >= context.max_trials:
                context.finish_reason = "max_trials"
            elif (
                self._generations_without_overall_improvement
                >= self.DEFAULT_EARLY_STOPPING_GENERATIONS
            ):
                context.finish_reason = "no_improvement"
            else:
                context.finish_reason = "completed"

    def __init__(
        self,
        model: str = "gpt-4o",
        model_parameters: dict[str, Any] | None = None,
        population_size: int = DEFAULT_POPULATION_SIZE,
        num_generations: int = DEFAULT_NUM_GENERATIONS,
        mutation_rate: float = DEFAULT_MUTATION_RATE,
        crossover_rate: float = DEFAULT_CROSSOVER_RATE,
        tournament_size: int = DEFAULT_TOURNAMENT_SIZE,
        elitism_size: int = DEFAULT_ELITISM_SIZE,
        adaptive_mutation: bool = DEFAULT_ADAPTIVE_MUTATION,
        enable_moo: bool = DEFAULT_ENABLE_MOO,
        enable_llm_crossover: bool = DEFAULT_ENABLE_LLM_CROSSOVER,
        output_style_guidance: str | None = None,
        infer_output_style: bool = False,
        n_threads: int = DEFAULT_NUM_THREADS,
        verbose: int = 1,
        seed: int = DEFAULT_SEED,
        name: str | None = None,
        prompt_overrides: PromptOverrides = None,
        skip_perfect_score: bool = True,
        perfect_score: float = 0.95,
    ) -> None:
        # Initialize base class first
        if sys.version_info >= (3, 13):
            warnings.warn(
                "Python 3.13 is not officially supported (python_requires <3.13). "
                "You may see asyncio teardown warnings. Prefer Python 3.12.",
                RuntimeWarning,
            )

        super().__init__(
            model=model,
            verbose=verbose,
            seed=seed,
            model_parameters=model_parameters,
            name=name,
            skip_perfect_score=skip_perfect_score,
            perfect_score=perfect_score,
            prompt_overrides=prompt_overrides,
        )
        self.population_size = population_size
        self.num_generations = num_generations
        self.mutation_rate = mutation_rate
        self.crossover_rate = crossover_rate
        self.tournament_size = tournament_size
        self.n_threads = n_threads
        self.elitism_size = elitism_size
        self.adaptive_mutation = adaptive_mutation
        self.enable_moo = enable_moo
        self.enable_llm_crossover = enable_llm_crossover
        self.seed = seed
        self.selection_policy = "tournament"
        self.output_style_guidance = (
            output_style_guidance
            if output_style_guidance is not None
            else self.DEFAULT_OUTPUT_STYLE_GUIDANCE
        )
        self.infer_output_style = infer_output_style
        self._best_fitness_history: list[float] = []
        self._generations_without_improvement = 0
        self._current_population: list[Any] = []
        self._generations_without_overall_improvement = 0
        self._best_primary_score_history: list[float] = []
        self._gens_since_pop_improvement: int = 0
        self._reporter: Any = None

        if self.seed is not None:
            random.seed(self.seed)
            np.random.seed(self.seed)
            logger.info(f"Global random seed set to: {self.seed}")
            # Note: DEAP tools generally respect random.seed().
            # TODO investigate if specific DEAP components require separate seeding

        if self.enable_moo:
            if not hasattr(creator, "FitnessMulti"):
                creator.create(
                    "FitnessMulti", base.Fitness, weights=self.DEFAULT_MOO_WEIGHTS
                )
            fitness_attr = creator.FitnessMulti
        else:
            if not hasattr(creator, "FitnessMax"):
                creator.create("FitnessMax", base.Fitness, weights=(1.0,))
            fitness_attr = creator.FitnessMax

        if (
            not hasattr(creator, "Individual")
            or getattr(creator.Individual, "fitness") != fitness_attr
        ):
            if hasattr(creator, "Individual"):
                del creator.Individual
            creator.create("Individual", dict, fitness=fitness_attr)

        # TODO: Use log on get metadata instead.
        logger.debug(
            f"Initialized EvolutionaryOptimizer with model: {model}, MOO_enabled: {self.enable_moo}, "
            f"LLM_Crossover: {self.enable_llm_crossover}, Seed: {self.seed}, "
            f"OutputStyleGuidance: '{self.output_style_guidance[:50]}...', "
            f"population_size: {self.population_size}, num_generations: {self.num_generations}, "
            f"mutation_rate: {self.mutation_rate}, crossover_rate: {self.crossover_rate}"
        )

    def get_optimizer_metadata(self) -> dict[str, Any]:
        # FIXME: Should we not use get_metadata or get_config instead?
        return {
            "population_size": self.population_size,
            "num_generations": self.num_generations,
            "mutation_rate": self.mutation_rate,
            "crossover_rate": self.crossover_rate,
            "tournament_size": self.tournament_size,
            "elitism_size": self.elitism_size,
            "adaptive_mutation": self.adaptive_mutation,
            "enable_moo": self.enable_moo,
            "enable_llm_crossover": self.enable_llm_crossover,
            "infer_output_style": self.infer_output_style,
            "output_style_guidance": self.output_style_guidance,
        }

    def _create_individual_from_prompts(
        self, prompts: dict[str, chat_prompt.ChatPrompt]
    ) -> Any:
        """Create a DEAP Individual from a dict of ChatPrompts."""
        prompts_messages = {name: p.get_messages() for name, p in prompts.items()}
        individual = creator.Individual(prompts_messages)
        setattr(
            individual,
            "prompts_metadata",
            {
                name: {
                    "tools": copy.deepcopy(p.tools),
                    "function_map": p.function_map,
                    "name": p.name,
                    "model": p.model,
                    "model_kwargs": copy.deepcopy(p.model_kwargs or {}),
                }
                for name, p in prompts.items()
            },
        )
        return individual

    def _individual_to_prompts(
        self, individual: Any
    ) -> dict[str, chat_prompt.ChatPrompt]:
        """Convert an Individual back to a dict of ChatPrompts."""
        prompts_metadata = getattr(individual, "prompts_metadata", {})
        result = {}
        for name, messages in individual.items():
            metadata = prompts_metadata.get(name, {})
            result[name] = chat_prompt.ChatPrompt(
                messages=messages,
                tools=metadata.get("tools"),
                function_map=metadata.get("function_map"),
                name=metadata.get("name", name),
                model=metadata.get("model"),
                model_parameters=metadata.get("model_kwargs"),
            )
        return result

    def _get_adaptive_mutation_rate(self) -> float:
        """Calculate adaptive mutation rate based on population diversity and progress."""
        if not self.adaptive_mutation or len(self._best_fitness_history) < 2:
            return self.mutation_rate

        # Calculate improvement rate
        recent_improvement = (
            self._best_fitness_history[-1] - self._best_fitness_history[-2]
        ) / abs(self._best_fitness_history[-2])

        # Calculate population diversity
        current_diversity = helpers.calculate_population_diversity(
            self._current_population
        )

        # Check for stagnation
        if recent_improvement < self.DEFAULT_RESTART_THRESHOLD:
            self._generations_without_improvement += 1
        else:
            self._generations_without_improvement = 0

        # Adjust mutation rate based on both improvement and diversity
        if self._generations_without_improvement >= self.DEFAULT_RESTART_GENERATIONS:
            # Significant stagnation - increase mutation significantly
            return min(self.mutation_rate * 2.5, self.DEFAULT_MAX_MUTATION_RATE)
        elif (
            recent_improvement < 0.01
            and current_diversity < DEFAULT_DIVERSITY_THRESHOLD
        ):
            # Both stagnating and low diversity - increase mutation significantly
            return min(self.mutation_rate * 2.0, self.DEFAULT_MAX_MUTATION_RATE)
        elif recent_improvement < 0.01:
            # Stagnating but good diversity - moderate increase
            return min(self.mutation_rate * 1.5, self.DEFAULT_MAX_MUTATION_RATE)
        elif recent_improvement > 0.05:
            # Good progress - decrease mutation
            return max(self.mutation_rate * 0.8, self.DEFAULT_MIN_MUTATION_RATE)
        return self.mutation_rate

    def _run_generation(
        self,
        generation_idx: int,
        population: list[Any],
        initial_prompts: dict[str, chat_prompt.ChatPrompt],
        hof: tools.HallOfFame,
        best_primary_score_overall: float,
    ) -> tuple[list[Any], int]:
        """Execute mating, mutation, evaluation and HoF update."""
        best_gen_score = 0.0

        # --- selection -------------------------------------------------
        if self.enable_moo:
            offspring = tools.selNSGA2(population, self.population_size)
        else:
            elites = tools.selBest(population, self.elitism_size)
            rest = tools.selTournament(
                population,
                len(population) - self.elitism_size,
                tournsize=self.tournament_size,
            )
            offspring = elites + rest

        # --- crossover -------------------------------------------------
        if self._reporter:
            self._reporter.performing_crossover()
        offspring = [copy.deepcopy(ind) for ind in offspring]
        for i in range(0, len(offspring), 2):
            if i + 1 < len(offspring):
                c1, c2 = offspring[i], offspring[i + 1]
                if random.random() < self.crossover_rate:
                    if self.enable_llm_crossover:
                        c1_new, c2_new = crossover_ops.llm_deap_crossover(
                            c1,
                            c2,
                            output_style_guidance=self.output_style_guidance,
                            model=self.model,
                            model_parameters=self.model_parameters,
                            verbose=self.verbose,
                            prompts=self._prompts,
                        )
                    else:
                        c1_new, c2_new = crossover_ops.deap_crossover(
                            c1,
                            c2,
                            verbose=self.verbose,
                        )
                    offspring[i], offspring[i + 1] = c1_new, c2_new
                    del offspring[i].fitness.values, offspring[i + 1].fitness.values

        # --- mutation --------------------------------------------------
        if self._reporter:
            self._reporter.performing_mutation()
        mut_rate = self._get_adaptive_mutation_rate()
        n_mutations = 0
        for i, ind in enumerate(offspring):
            if random.random() < mut_rate:
                new_ind = mutation_ops.deap_mutation(
                    individual=ind,
                    current_population=self._current_population,
                    output_style_guidance=self.output_style_guidance,
                    initial_prompts=initial_prompts,
                    model=self.model,
                    model_parameters=self.model_parameters,
                    diversity_threshold=DEFAULT_DIVERSITY_THRESHOLD,
                    optimization_id=self.current_optimization_id,
                    verbose=self.verbose,
                    prompts=self._prompts,
                )
                offspring[i] = new_ind
                del offspring[i].fitness.values
                n_mutations += 1

        # --- evaluation ------------------------------------------------
        invalid = [ind for ind in offspring if not ind.fitness.valid]
        if self._reporter:
            self._reporter.performing_evaluation(len(invalid))
        for ind in invalid:
            fit = self._deap_evaluate_individual_fitness(ind)
            if self.enable_moo:
                ind.fitness.values = fit
            else:
                ind.fitness.values = tuple([fit[0]])
            best_gen_score = max(best_gen_score, fit[0])

        # --- update HoF -----------------------------------------------
        hof.update(offspring)
        logger.debug(
            f"Generation {generation_idx}: best_score={best_gen_score:.4f}, "
            f"overall_best={best_primary_score_overall:.4f}"
        )

        return offspring, len(invalid)

    def _population_best_score(self, population: list[Any]) -> float:
        """Return highest primary-objective score among *valid* individuals."""
        valid_scores = [
            ind.fitness.values[0] for ind in population if ind.fitness.valid
        ]
        return max(valid_scores, default=0.0)

    def run_optimization(
        self,
        context: OptimizationContext,
    ) -> AlgorithmResult:
        """
        Run the Evolutionary optimization algorithm.

        Uses genetic algorithms (via DEAP) to evolve prompts through:
        1. Selection (tournament or NSGA-II for MOO)
        2. Crossover (LLM-based or traditional)
        3. Mutation (adaptive rate based on population diversity)
        4. Evaluation and hall of fame tracking

        Args:
            context: The optimization context with prompts, dataset, metric, etc.

        Returns:
            AlgorithmResult with best prompts, score, history, and metadata.
        """
        optimizable_prompts = context.prompts
        dataset = context.dataset
        metric = context.metric
        experiment_config = context.experiment_config
        max_trials = context.max_trials

        # Initialize progress tracking for display
        self._current_round = 0
        self._total_rounds = self.num_generations

        self._history_builder.clear()
        self._best_fitness_history = []
        self._generations_without_improvement = 0
        self._current_population = []
        self._generations_without_overall_improvement = 0

        if self.enable_moo:

            def _deap_evaluate_individual_fitness(
                individual: Any,
            ) -> tuple[float, ...]:
                # Check if we should skip (max_trials or early stop already triggered)
                if self._should_stop_context(context):
                    return (-float("inf"), float("inf"))

                # Convert individual to ChatPrompt dict and use centralized evaluate()
                prompts_bundle = self._individual_to_prompts(individual)
                primary_fitness_score = self.evaluate(
                    prompts_bundle,
                    experiment_config=(experiment_config or {}).copy(),
                )

                # Compute prompt length for MOO (secondary objective)
                prompt_length = float(len(str(json.dumps(dict(individual)))))

                return (primary_fitness_score, prompt_length)

        else:

            def _deap_evaluate_individual_fitness(
                individual: Any,
            ) -> tuple[float, ...]:
                # Check if we should skip (max_trials or early stop already triggered)
                if self._should_stop_context(context):
                    return (-float("inf"),)

                # Convert individual to ChatPrompt dict and use centralized evaluate()
                prompts_bundle = self._individual_to_prompts(individual)
                fitness_score = self.evaluate(
                    prompts_bundle,
                    experiment_config=(experiment_config or {}).copy(),
                )

                return (fitness_score,)

        self._deap_evaluate_individual_fitness = _deap_evaluate_individual_fitness

        # Use baseline score from context (computed by base class)
        initial_primary_score = cast(float, context.baseline_score)
        initial_prompts_messages = {
            name: p.get_messages() for name, p in optimizable_prompts.items()
        }
        initial_length = float(len(json.dumps(initial_prompts_messages)))

        best_primary_score_overall = initial_primary_score
        best_prompts_overall = optimizable_prompts

        # Step 3. Define the output style guide
        effective_output_style_guidance = self.output_style_guidance
        if self.infer_output_style and (
            self.output_style_guidance is None
            or self.output_style_guidance == self.DEFAULT_OUTPUT_STYLE_GUIDANCE
        ):
            # If user wants inference AND hasn't provided a specific custom guidance
            inferred_style = style_ops.infer_output_style_from_dataset(
                dataset=dataset,
                model=self.model,
                model_parameters=self.model_parameters,
                verbose=self.verbose,
                prompts=self._prompts,
            )
            if inferred_style:
                effective_output_style_guidance = inferred_style
                # Update self.output_style_guidance for this run so dynamic prompt methods use it
                self.output_style_guidance = inferred_style
            else:
                logger.warning(
                    "Failed to infer output style, using default or user-provided guidance."
                )

        # Ensure self.output_style_guidance is set to the effective one for the rest of the methods for this run
        # (It might have been None if user passed None and infer_output_style was False)
        if self.output_style_guidance is None:
            # Fallback if still None
            self.output_style_guidance = self.DEFAULT_OUTPUT_STYLE_GUIDANCE

        # Step 4. Initialize population
        # Generate variations for each prompt in the dict
        prompt_variations: dict[str, list[chat_prompt.ChatPrompt]] = {}
        for prompt_name, prompt_obj in optimizable_prompts.items():
            variations = population_ops.initialize_population(
                prompt=prompt_obj,
                output_style_guidance=effective_output_style_guidance,
                model=self.model,
                model_parameters=self.model_parameters,
                optimization_id=self.current_optimization_id,
                population_size=self.population_size,
                verbose=self.verbose,
                prompts=self._prompts,
            )
            prompt_variations[prompt_name] = variations

        # Combine variations into individuals (zip variations together)
        deap_population = []
        for i in range(self.population_size):
            prompts_for_individual = {
                name: variations[i % len(variations)]
                for name, variations in prompt_variations.items()
            }
            deap_population.append(
                self._create_individual_from_prompts(prompts_for_individual)
            )
        deap_population = deap_population[: self.population_size]

        # Step 5. Initialize the hall of fame (Pareto front for MOO) and stats for MOO or SO
        if self.enable_moo:
            hof = tools.ParetoFront()
        else:
            # Single-objective
            hof = tools.HallOfFame(self.DEFAULT_HALL_OF_FAME_SIZE)

        # Step 6. Evaluate the initial population
        logger.debug("Evaluating initial population")
        fitnesses: list[Any] = list(
            map(self._deap_evaluate_individual_fitness, deap_population)
        )
        _best_score = max(best_primary_score_overall, max([x[0] for x in fitnesses]))

        for ind, fit in zip(deap_population, fitnesses):
            if self.enable_moo:
                ind.fitness.values = fit
            else:
                ind.fitness.values = tuple([fit[0]])
        logger.debug(f"Initial population evaluated, best_score={_best_score:.4f}")

        hof.update(deap_population)

        if hof and len(hof) > 0:
            if self.enable_moo:
                current_best_for_primary: Any = max(
                    hof, key=lambda ind: ind.fitness.values[0]
                )
                best_primary_score_overall = current_best_for_primary.fitness.values[0]
                best_prompts_overall = self._individual_to_prompts(
                    current_best_for_primary
                )
            else:
                # Single-objective
                current_best_on_front = hof[0]
                best_primary_score_overall = current_best_on_front.fitness.values[0]
                best_prompts_overall = self._individual_to_prompts(
                    current_best_on_front
                )

            if self.enable_moo:
                logger.info(
                    f"Gen {0}: New best primary score: {best_primary_score_overall:.4f}, Prompts: {len(best_prompts_overall)}"
                )
            else:
                logger.info(
                    f"Gen {0}: New best score: {best_primary_score_overall:.4f}"
                )

        self.set_default_dataset_split(context.dataset_split or "train")
        round_handle = self.begin_round(improvement=0.0)
        generation_idx = 0
        self.record_candidate_entry(
            prompt_or_payload=best_prompts_overall,
            score=best_primary_score_overall,
            id="gen0_ind0",
        )
        self.post_candidate(
            best_prompts_overall,
            score=best_primary_score_overall,
            trial_index=context.trials_completed,
            round_handle=round_handle,
        )
        selection_meta = None
        pareto_front = None
        if self.enable_moo:
            pareto_front = (
                [
                    {
                        "score": ind.fitness.values[0],
                        "length": ind.fitness.values[1],
                        "id": f"gen{generation_idx}_hof{idx}",
                    }
                    for idx, ind in enumerate(hof)
                ]
                if hof
                else []
            )
            selection_meta = {
                "selection_policy": getattr(self, "selection_policy", "tournament"),
                "pareto_front": pareto_front,
            }
            # Stash pareto/selection meta on the state so we don't re-pass args.
            self.set_pareto_front(pareto_front)
            self.set_selection_meta(selection_meta)

        self.post_round(
            round_handle,
            best_score=best_primary_score_overall,
            best_prompt=best_prompts_overall,
            dataset_split=context.dataset_split
            if hasattr(context, "dataset_split")
            else None,
            stop_reason=context.finish_reason,
            extras={
                "stopped": context.should_stop,
                "stop_reason": context.finish_reason,
                "improvement": 0.0,
            },
        )

        generation_idx = 0
        with reporting.start_evolutionary_algo(verbose=self.verbose) as evo_reporter:
            self._set_reporter(evo_reporter)
            try:
                for generation_idx in range(1, self.num_generations + 1):
                    # Update progress tracking for display
                    self._current_round = generation_idx - 1  # 0-based internally
                    # Check should_stop flag at start of each generation (includes max_trials/perfect_score)
                    if self._should_stop_context(context):
                        if context.finish_reason == "max_trials":
                            logger.info(
                                f"Stopping optimization: max_trials ({max_trials}) reached after {generation_idx - 1} generations"
                            )
                        break

                    evo_reporter.start_gen(generation_idx, self.num_generations)
                    round_handle = self.begin_round()

                    curr_best_score = self._population_best_score(deap_population)

                    # ---------- restart logic -------------------------------------
                    (
                        should_restart,
                        gens_since_pop_improvement,
                        best_primary_score_history,
                    ) = population_ops.should_restart_population(
                        curr_best=curr_best_score,
                        best_primary_score_history=self._best_primary_score_history,
                        gens_since_pop_improvement=self._gens_since_pop_improvement,
                        default_restart_threshold=self.DEFAULT_RESTART_THRESHOLD,
                        default_restart_generations=self.DEFAULT_RESTART_GENERATIONS,
                    )
                    self._gens_since_pop_improvement = gens_since_pop_improvement
                    self._best_primary_score_history = best_primary_score_history

                    if should_restart:
                        evo_reporter.restart_population(
                            self.DEFAULT_RESTART_GENERATIONS
                        )
                        deap_population = population_ops.restart_population(
                            optimizer=self,
                            hof=hof,
                            population=deap_population,
                            best_prompts_so_far=best_prompts_overall,
                        )

                    # ---------- run one generation --------------------------------
                    deap_population, _ = self._run_generation(
                        generation_idx,
                        deap_population,
                        optimizable_prompts,
                        hof,
                        best_primary_score_overall,
                    )

                    # -------- update best-prompt bookkeeping -------------------------
                    if hof:
                        if self.enable_moo:
                            current_best_ind = max(
                                hof, key=lambda ind: ind.fitness.values[0]
                            )
                        else:
                            current_best_ind = hof[0]

                        updated_best_primary_score = current_best_ind.fitness.values[0]
                        if updated_best_primary_score > best_primary_score_overall:
                            best_primary_score_overall = updated_best_primary_score
                            best_prompts_overall = self._individual_to_prompts(
                                current_best_ind
                            )
                            self._generations_without_overall_improvement = 0
                        else:
                            self._generations_without_overall_improvement += 1
                    else:
                        self._generations_without_overall_improvement += 1

                    # ---------- early-stopping check ------------------------------
                    if (
                        self._generations_without_overall_improvement
                        >= self.DEFAULT_EARLY_STOPPING_GENERATIONS
                    ):
                        logger.info(
                            "No overall improvement for %d generations – early stopping at gen %d.",
                            self.DEFAULT_EARLY_STOPPING_GENERATIONS,
                            generation_idx,
                        )
                        break

                    # Report end of generation
                    reporting.end_gen(
                        generation_idx,
                        best_primary_score_overall,
                        initial_primary_score,
                        verbose=self.verbose,
                    )

                    # History logging for this transition
                    candidate_entries: list[dict[str, Any]] = []
                    valid_individuals = [
                        ind for ind in deap_population if ind.fitness.valid
                    ]
                    first_trial_index = (
                        context.trials_completed - len(valid_individuals) + 1
                    )
                    valid_idx = 0
                    for ind in deap_population:
                        if not ind.fitness.valid:
                            continue
                        candidate_prompts = self._individual_to_prompts(ind)
                        primary_score = ind.fitness.values[0]
                        metrics: dict[str, Any] | None = None
                        if self.enable_moo and len(ind.fitness.values) > 1:
                            metrics = {
                                "primary": ind.fitness.values[0],
                                "length": ind.fitness.values[1],
                            }
                        entry = self.record_candidate_entry(
                            prompt_or_payload=candidate_prompts,
                            score=primary_score,
                            id=f"gen{generation_idx}_ind{valid_idx}",
                            metrics=metrics,
                        )
                        candidate_entries.append(entry)
                        self.post_candidate(
                            candidate_prompts,
                            score=primary_score,
                            trial_index=first_trial_index + valid_idx,
                            metrics=metrics,
                            round_handle=round_handle,
                        )
                        valid_idx += 1

                    pareto_front = None
                    selection_meta = None
                    if self.enable_moo:
                        pareto_front = (
                            [
                                {
                                    "primary": ind.fitness.values[0],
                                    "length": ind.fitness.values[1],
                                }
                                for ind in hof
                            ]
                            if hof
                            else []
                        )
                        selection_meta = {
                            "selection_policy": getattr(
                                self, "selection_policy", "tournament"
                            ),
                            "pareto_front": pareto_front,
                        }
                    self.post_round(
                        round_handle=round_handle,
                        best_score=best_primary_score_overall,
                        best_candidate=best_prompts_overall,
                        stop_reason=context.finish_reason,
                        candidates=candidate_entries,
                        pareto_front=pareto_front,
                        selection_meta=selection_meta,
                        extras={
                            "stopped": context.should_stop,
                            "stop_reason": context.finish_reason,
                            "improvement": (
                                (best_primary_score_overall - initial_primary_score)
                                / abs(initial_primary_score)
                                if initial_primary_score and initial_primary_score != 0
                                else (1.0 if best_primary_score_overall > 0 else 0.0)
                            ),
                            "best_so_far": best_primary_score_overall,
                        },
                    )
            finally:
                if context.finish_reason == "max_trials":
                    reporting.display_message(
                        f"Stopped early: max_trials reached after round {self._current_round + 1}",
                        verbose=self.verbose,
                    )
                self._clear_reporter()

        # finish_reason is handled by _finalize_finish_reason override

        final_details = {}

        if self.enable_moo:
            final_results_log = "Pareto Front Solutions:\n"
            if hof and len(hof) > 0:
                sorted_hof = sorted(
                    hof, key=lambda ind: ind.fitness.values[0], reverse=True
                )
                for i, sol in enumerate(sorted_hof):
                    final_results_log += f"  Solution {i + 1}: Primary Score={sol.fitness.values[0]:.4f}, Length={sol.fitness.values[1]:.0f}, Prompts={len(sol)}\n"
                best_overall_solution = sorted_hof[0]
                final_best_prompts = self._individual_to_prompts(best_overall_solution)
                final_primary_score = best_overall_solution.fitness.values[0]
                final_length = best_overall_solution.fitness.values[1]
                logger.info(final_results_log)
                logger.info(
                    f"Best prompts (highest primary score from Pareto front): {len(final_best_prompts)} prompts"
                )
                logger.info(
                    f"  Primary Score ({metric.__name__}): {final_primary_score:.4f}"
                )
                logger.info(f"  Length: {final_length:.0f}")
                final_details.update(
                    {
                        "initial_primary_score": initial_primary_score,
                        "initial_length": initial_length,
                        "final_prompts": final_best_prompts,
                        "final_primary_score_representative": final_primary_score,
                        "final_length_representative": final_length,
                        "pareto_front_solutions": (
                            [
                                {
                                    "prompt": str(dict(ind)),
                                    "score": ind.fitness.values[0],
                                    "length": ind.fitness.values[1],
                                }
                                for ind in hof
                            ]
                            if hof
                            else []
                        ),
                    }
                )
            else:
                # MOO: ParetoFront is empty. Reporting last known best and fallback values
                logger.warning("MOO: ParetoFront is empty. Reporting last known best.")
                final_best_prompts = best_prompts_overall
                final_primary_score = best_primary_score_overall
                all_messages = {
                    name: p.get_messages() for name, p in final_best_prompts.items()
                }
                final_length = float(len(json.dumps(all_messages)))
                final_details.update(
                    {
                        "initial_primary_score": initial_primary_score,
                        "initial_length": initial_length,
                        "final_prompts": final_best_prompts,
                        "final_primary_score_representative": final_primary_score,
                        "final_length_representative": final_length,
                        "pareto_front_solutions": [],
                    }
                )
        else:
            # Single-objective
            final_best_prompts = best_prompts_overall
            final_primary_score = best_primary_score_overall
            logger.info(
                f"Final best prompts from Hall of Fame: {len(final_best_prompts)} prompts"
            )
            logger.info(
                f"Final best score ({metric.__name__}): {final_primary_score:.4f}"
            )
            initial_messages = {
                name: p.get_messages() for name, p in optimizable_prompts.items()
            }
            final_details.update(
                {
                    "initial_prompts": initial_messages,
                    "initial_score": initial_primary_score,
                    "initial_score_for_display": initial_primary_score,
                    "final_prompts": final_best_prompts,
                    "final_score": final_primary_score,
                }
            )

        logger.info(f"Total LLM calls during optimization: {self.llm_call_counter}")
        logger.info(f"Total prompt evaluations: {context.trials_completed}")

        # Collect tools from all final prompts
        all_final_tools = {}
        for name, p in final_best_prompts.items():
            if p.tools:
                all_final_tools[name] = p.tools

        # Build run-specific metadata (base class adds get_metadata() separately)
        metadata: dict[str, Any] = {
            "total_generations_run": generation_idx,
            "elitism_size": (
                self.elitism_size if not self.enable_moo else "N/A (MOO uses NSGA-II)"
            ),
            "adaptive_mutation": self.adaptive_mutation,
            "llm_crossover_enabled": self.enable_llm_crossover,
            "seed": self.seed,
            "user_output_style_guidance": self.output_style_guidance,
            "infer_output_style": self.infer_output_style,
            "final_effective_output_style_guidance": effective_output_style_guidance,
        }
        metadata.update(final_details)
        if all_final_tools:
            metadata["final_tools"] = all_final_tools

        return AlgorithmResult(
            best_prompts=final_best_prompts,
            best_score=final_primary_score,
            history=self.get_history_entries(),
            metadata=metadata,
        )
