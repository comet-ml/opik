import copy
import json
import logging
import random
from typing import Any, cast
import sys
import warnings

import numpy as np
import opik

# DEAP imports
from deap import base, tools
from deap import creator as _creator
from opik.api_objects import optimization
from opik.environment import get_tqdm_for_current_environment

from opik_optimizer.base_optimizer import BaseOptimizer, OptimizationRound
from ...api_objects import chat_prompt
from ...api_objects.types import MetricFunction
from opik_optimizer.optimization_result import OptimizationResult
from opik_optimizer.agents import OptimizableAgent, LiteLLMAgent

from . import reporting
from .ops import crossover_ops, mutation_ops, style_ops, population_ops, evaluation_ops
from . import helpers
from . import prompts as evo_prompts

logger = logging.getLogger(__name__)
tqdm = get_tqdm_for_current_environment()

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
        self.output_style_guidance = (
            output_style_guidance
            if output_style_guidance is not None
            else self.DEFAULT_OUTPUT_STYLE_GUIDANCE
        )
        self.infer_output_style = infer_output_style
        self._current_generation = 0
        self._best_fitness_history: list[float] = []
        self._generations_without_improvement = 0
        self._current_population: list[Any] = []
        self._generations_without_overall_improvement = 0
        self._best_primary_score_history: list[float] = []
        self._gens_since_pop_improvement: int = 0

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

        logger.debug(
            f"Initialized EvolutionaryOptimizer with model: {model}, MOO_enabled: {self.enable_moo}, "
            f"LLM_Crossover: {self.enable_llm_crossover}, Seed: {self.seed}, "
            f"OutputStyleGuidance: '{self.output_style_guidance[:50]}...', "
            f"population_size: {self.population_size}, num_generations: {self.num_generations}, "
            f"mutation_rate: {self.mutation_rate}, crossover_rate: {self.crossover_rate}"
        )

    def get_optimizer_metadata(self) -> dict[str, Any]:
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
        """Create a DEAP Individual from a dict of ChatPrompts.

        The Individual content is a dict mapping prompt names to their messages.
        Metadata (tools, function_map) is stored in a 'prompts_metadata' attribute.
        """
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
        report: Any,
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
        report.performing_crossover()
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
                        )
                    else:
                        c1_new, c2_new = crossover_ops.deap_crossover(
                            c1,
                            c2,
                            verbose=self.verbose,
                        )
                    offspring[i], offspring[i + 1] = c1_new, c2_new
                    del offspring[i].fitness.values, offspring[i + 1].fitness.values
        reporting.display_success(
            "      Crossover successful, prompts have been combined and edited.\n│",
            verbose=self.verbose,
        )

        # --- mutation --------------------------------------------------
        report.performing_mutation()
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
                )
                offspring[i] = new_ind
                del offspring[i].fitness.values
                n_mutations += 1
        reporting.display_success(
            f"      Mutation successful, {n_mutations} prompts have been edited.\n│",
            verbose=self.verbose,
        )

        # --- evaluation ------------------------------------------------
        invalid = [ind for ind in offspring if not ind.fitness.valid]
        report.performing_evaluation(len(invalid))
        for ind_idx, ind in enumerate(invalid):
            fit = self._deap_evaluate_individual_fitness(ind)
            if self.enable_moo:
                ind.fitness.values = fit
            else:
                ind.fitness.values = tuple([fit[0]])
            best_gen_score = max(best_gen_score, fit[0])

            report.performed_evaluation(ind_idx, ind.fitness.values[0])

        # --- update HoF & reporter ------------------------------------
        hof.update(offspring)
        reporting.end_gen(
            generation_idx,
            best_gen_score,
            best_primary_score_overall,
            verbose=self.verbose,
        )

        return offspring, len(invalid)

    def _population_best_score(self, population: list[Any]) -> float:
        """Return highest primary-objective score among *valid* individuals."""
        valid_scores = [
            ind.fitness.values[0] for ind in population if ind.fitness.valid
        ]
        return max(valid_scores, default=0.0)

    def optimize_prompt(
        self,
        prompt: chat_prompt.ChatPrompt | dict[str, chat_prompt.ChatPrompt],
        dataset: opik.Dataset,
        metric: MetricFunction,
        agent: OptimizableAgent | None = None,
        experiment_config: dict | None = None,
        n_samples: int | None = None,
        auto_continue: bool = False,
        project_name: str = "Optimization",
        optimization_id: str | None = None,
        validation_dataset: opik.Dataset | None = None,
        max_trials: int = 10,
        *args: Any,
        **kwargs: Any,
    ) -> OptimizationResult:
        """
        Args:
            prompt: The prompt to optimize (single ChatPrompt or dict of ChatPrompts).
            dataset: Dataset used to evaluate each candidate prompt.
            metric: Objective function receiving `(dataset_item, llm_output)`.
            agent: Optional agent instance for executing prompts. If None, uses LiteLLMAgent.
            experiment_config: Optional experiment configuration metadata.
            n_samples: Optional number of dataset items to evaluate per prompt.
            auto_continue: Whether to continue automatically after each generation.
            project_name: Opik project name for logging traces (default: "Optimization").
            optimization_id: Optional ID for the Opik optimization run; when provided it
                must be a valid UUIDv7 string.
            validation_dataset: Optional validation dataset (not yet supported by this optimizer).
            max_trials: Maximum number of prompt evaluations allowed.
        """
        # Convert single prompt to dict format for internal processing
        optimizable_prompts: dict[str, chat_prompt.ChatPrompt]
        is_single_prompt_optimization: bool
        if isinstance(prompt, chat_prompt.ChatPrompt):
            optimizable_prompts = {prompt.name: prompt}
            is_single_prompt_optimization = True
        else:
            optimizable_prompts = prompt
            is_single_prompt_optimization = False

        # Logic on which dataset to use for scoring
        if validation_dataset is not None:
            logger.warning(
                f"{self.__class__.__name__} currently does not support validation dataset. "
                f"Using `dataset` (training) for now. Ignoring `validation_dataset` parameter."
            )
        evaluation_dataset = (
            validation_dataset if validation_dataset is not None else dataset
        )

        # Use base class validation and setup methods
        self._validate_optimization_inputs(
            optimizable_prompts, dataset, metric, support_content_parts=True
        )

        if agent is None:
            agent = LiteLLMAgent(project_name=project_name)
        self.agent = agent
        evaluation_kwargs: dict[str, Any] = {}

        # Set project name from parameter
        self.project_name = project_name

        # Step 0. Start Opik optimization run
        opik_optimization_run: optimization.Optimization | None = None
        try:
            opik_optimization_run = self.opik_client.create_optimization(
                dataset_name=dataset.name,
                objective_name=metric.__name__,
                metadata=self._build_optimization_metadata(),
                name=self.name,
                optimization_id=optimization_id,
            )
            self.current_optimization_id = (
                opik_optimization_run.id if opik_optimization_run is not None else None
            )
        except Exception as e:
            logger.warning(f"Opik server error: {e}. Continuing without Opik tracking.")
            self.current_optimization_id = None

        reporting.display_header(
            algorithm=self.__class__.__name__,
            optimization_id=self.current_optimization_id,
            dataset_id=dataset.id,
            verbose=self.verbose,
        )

        reporting.display_configuration(
            optimizable_prompts,
            {
                "optimizer": f"{'DEAP MOO' if self.enable_moo else 'DEAP SO'} Evolutionary Optimization",
                "population_size": self.population_size,
                "generations": self.num_generations,
                "mutation_rate": self.mutation_rate,
                "crossover_rate": self.crossover_rate,
            },
            verbose=self.verbose,
        )

        # Step 1. Step variables and define fitness function
        self._reset_counters()  # Reset counters for run
        trials_used = [0]  # Use list for closure mutability
        self._history: list[OptimizationRound] = []
        self._current_generation = 0
        self._best_fitness_history = []
        self._generations_without_improvement = 0
        self._current_population = []
        self._generations_without_overall_improvement = 0

        # Store prompts metadata for fitness evaluation (closure capture)
        prompts_metadata = {
            name: {
                "tools": copy.deepcopy(p.tools),
                "function_map": p.function_map,
                "name": p.name,
            }
            for name, p in optimizable_prompts.items()
        }

        if self.enable_moo:

            def _deap_evaluate_individual_fitness(
                individual: Any,
            ) -> tuple[float, ...]:
                # Check if we've hit the limit
                if trials_used[0] >= max_trials:
                    logger.debug(
                        f"Skipping evaluation - max_trials ({max_trials}) reached"
                    )
                    return (-float("inf"), float("inf"))  # Worst possible fitness

                trials_used[0] += 1

                # Individual is a dict mapping prompt_name -> messages
                primary_fitness_score = evaluation_ops.evaluate_bundle(
                    self,
                    bundle_messages=dict(individual),
                    prompts_metadata=prompts_metadata,
                    dataset=evaluation_dataset,
                    metric=metric,
                    n_samples=n_samples,
                    experiment_config=(experiment_config or {}).copy(),
                    optimization_id=self.current_optimization_id,
                    verbose=0,
                    **evaluation_kwargs,
                )
                prompt_length = float(len(str(json.dumps(dict(individual)))))
                return (primary_fitness_score, prompt_length)

        else:
            # Single-objective
            def _deap_evaluate_individual_fitness(
                individual: Any,
            ) -> tuple[float, ...]:
                # Check if we've hit the limit
                if trials_used[0] >= max_trials:
                    logger.debug(
                        f"Skipping evaluation - max_trials ({max_trials}) reached"
                    )
                    return (-float("inf"),)  # Worst possible fitness

                trials_used[0] += 1

                # Individual is a dict mapping prompt_name -> messages
                fitness_score = evaluation_ops.evaluate_bundle(
                    self,
                    bundle_messages=dict(individual),
                    prompts_metadata=prompts_metadata,
                    dataset=evaluation_dataset,
                    metric=metric,
                    n_samples=n_samples,
                    experiment_config=(experiment_config or {}).copy(),
                    optimization_id=self.current_optimization_id,
                    verbose=0,
                    **evaluation_kwargs,
                )
                return (fitness_score,)

        self._deap_evaluate_individual_fitness = _deap_evaluate_individual_fitness

        # Step 2. Compute the initial performance of the prompt
        with reporting.baseline_performance(
            verbose=self.verbose
        ) as report_baseline_performance:
            # Create initial individual from all prompts
            initial_individual = self._create_individual_from_prompts(
                optimizable_prompts
            )
            initial_eval_result = self._deap_evaluate_individual_fitness(
                initial_individual
            )
            initial_primary_score = initial_eval_result[0]
            initial_prompts_messages = {
                name: p.get_messages() for name, p in optimizable_prompts.items()
            }
            initial_length = (
                initial_eval_result[1]
                if self.enable_moo
                else float(len(json.dumps(initial_prompts_messages)))
            )

            trials_used[0] = 0
            best_primary_score_overall = initial_primary_score
            best_prompts_overall = optimizable_prompts
            report_baseline_performance.set_score(initial_primary_score)

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
        with reporting.evaluate_initial_population(
            verbose=self.verbose
        ) as report_initial_population:
            fitnesses: list[Any] = list(
                map(self._deap_evaluate_individual_fitness, deap_population)
            )
            _best_score = max(
                best_primary_score_overall, max([x[0] for x in fitnesses])
            )

            for i, ind, fit in zip(
                range(len(deap_population)), deap_population, fitnesses
            ):
                if self.enable_moo:
                    ind.fitness.values = fit
                else:
                    ind.fitness.values = tuple([fit[0]])
                report_initial_population.set_score(i, fit[0], _best_score)

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

            # Use first prompt as representative for logging
            representative_prompt = list(best_prompts_overall.values())[0]
            if self.enable_moo:
                logger.info(
                    f"Gen {0}: New best primary score: {best_primary_score_overall:.4f}, Prompts: {len(best_prompts_overall)}"
                )
            else:
                logger.info(
                    f"Gen {0}: New best score: {best_primary_score_overall:.4f}"
                )

            # Simplified history logging for this transition
            initial_round_data = OptimizationRound(
                round_number=0,
                current_prompt=representative_prompt,  # Representative best
                current_score=best_primary_score_overall,
                generated_prompts=[
                    {
                        "prompt": representative_prompt,
                        "score": best_primary_score_overall,
                        "trial_scores": [best_primary_score_overall],
                    }
                ],
                best_prompt=representative_prompt,
                best_score=best_primary_score_overall,
                improvement=0.0,
            )
            self._add_to_history(initial_round_data)

        with reporting.start_evolutionary_algo(
            verbose=self.verbose
        ) as report_evolutionary_algo:
            for generation_idx in range(1, self.num_generations + 1):
                # Check if we've exhausted our evaluation budget
                if trials_used[0] >= max_trials:
                    logger.info(
                        f"Stopping optimization: max_trials ({max_trials}) reached after {generation_idx - 1} generations"
                    )
                    break

                report_evolutionary_algo.start_gen(generation_idx, self.num_generations)

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
                    report_evolutionary_algo.restart_population(
                        self.DEFAULT_RESTART_GENERATIONS
                    )
                    deap_population = population_ops.restart_population(
                        optimizer=self,
                        hof=hof,
                        population=deap_population,
                        best_prompts_so_far=best_prompts_overall,
                    )

                # ---------- run one generation --------------------------------
                deap_population, invalid_count = self._run_generation(
                    generation_idx,
                    deap_population,
                    optimizable_prompts,
                    hof,
                    report_evolutionary_algo,
                    best_primary_score_overall,
                )

                # -------- update best-prompt bookkeeping -------------------------
                previous_best_primary_score_for_gen = best_primary_score_overall
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
                    elif (
                        updated_best_primary_score
                        == previous_best_primary_score_for_gen
                    ):
                        self._generations_without_overall_improvement += 1
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

                # History logging for this transition
                representative_prompt = list(best_prompts_overall.values())[0]
                gen_round_data = OptimizationRound(
                    round_number=generation_idx,
                    current_prompt=representative_prompt,  # Representative best
                    current_score=best_primary_score_overall,
                    generated_prompts=[
                        {"prompt": str(dict(ind)), "score": ind.fitness.values[0]}
                        for ind in deap_population
                        if ind.fitness.valid
                    ],
                    best_prompt=representative_prompt,
                    best_score=best_primary_score_overall,
                    improvement=(
                        (best_primary_score_overall - initial_primary_score)
                        / abs(initial_primary_score)
                        if initial_primary_score and initial_primary_score != 0
                        else (1.0 if best_primary_score_overall > 0 else 0.0)
                    ),
                )
                self._add_to_history(gen_round_data)

        stopped_early_flag = (
            self._generations_without_overall_improvement
            >= self.DEFAULT_EARLY_STOPPING_GENERATIONS
        )
        final_details = {}
        initial_score_for_display = initial_primary_score

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
        logger.info(f"Total prompt evaluations: {trials_used[0]}")
        if opik_optimization_run:
            try:
                opik_optimization_run.update(status="completed")
                logger.info(
                    f"Opik Optimization run {self.current_optimization_id} status updated to completed."
                )
            except Exception as e:
                logger.warning(f"Failed to update Opik Optimization run status: {e}")

        # Add final details
        final_details.update(
            {
                "total_generations_run": generation_idx + 1,
                "num_generations": self.num_generations,
                "population_size": self.population_size,
                "mutation_probability": self.mutation_rate,
                "crossover_probability": self.crossover_rate,
                "elitism_size": (
                    self.elitism_size
                    if not self.enable_moo
                    else "N/A (MOO uses NSGA-II)"
                ),
                "adaptive_mutation": self.adaptive_mutation,
                "metric_name": metric.__name__,
                "model": self.model,
                "moo_enabled": self.enable_moo,
                "llm_crossover_enabled": self.enable_llm_crossover,
                "seed": self.seed,
                "prompt_type": "single_string_ga",
                "initial_score_for_display": initial_score_for_display,
                "temperature": self.model_parameters.get("temperature"),
                "stopped_early": stopped_early_flag,
                "rounds": self.get_history(),
                "user_output_style_guidance": self.output_style_guidance,
                "infer_output_style_requested": self.infer_output_style,
                "final_effective_output_style_guidance": effective_output_style_guidance,
                "infer_output_style": self.infer_output_style,
                "trials_used": trials_used[0],
            }
        )

        # Return the OptimizationResult
        # Display result - show single prompt or all prompts based on optimization type
        if is_single_prompt_optimization:
            display_prompt: (
                chat_prompt.ChatPrompt | dict[str, chat_prompt.ChatPrompt]
            ) = list(final_best_prompts.values())[0]
        else:
            display_prompt = final_best_prompts
        reporting.display_result(
            initial_score=initial_score_for_display,
            best_score=final_primary_score,
            prompt=display_prompt,
            verbose=self.verbose,
        )

        # Collect tools from all final prompts
        all_final_tools = {}
        for name, p in final_best_prompts.items():
            if p.tools:
                all_final_tools[name] = p.tools
        if all_final_tools:
            final_details["final_tools"] = all_final_tools

        # Convert result format based on input type
        if is_single_prompt_optimization:
            # Return single prompt (first one from dict)
            result_prompt: (
                chat_prompt.ChatPrompt | dict[str, chat_prompt.ChatPrompt]
            ) = list(final_best_prompts.values())[0]
            result_initial_prompt: (
                chat_prompt.ChatPrompt | dict[str, chat_prompt.ChatPrompt]
            ) = list(optimizable_prompts.values())[0]
        else:
            # Return all prompts as dict
            result_prompt = final_best_prompts
            result_initial_prompt = optimizable_prompts

        return OptimizationResult(
            optimizer=self.__class__.__name__,
            prompt=result_prompt,
            score=final_primary_score,
            initial_prompt=result_initial_prompt,
            initial_score=initial_primary_score,
            metric_name=metric.__name__,
            details=final_details,
            history=[x.model_dump() for x in self.get_history()],
            llm_calls=self.llm_call_counter,
            tool_calls=self.tool_call_counter,
            dataset_id=dataset.id,
            optimization_id=self.current_optimization_id,
        )

    # Override prompt builders to centralize strings in prompts.py
    def _get_reasoning_system_prompt_for_variation(self) -> str:
        return evo_prompts.variation_system_prompt(self.output_style_guidance)

    def _get_llm_crossover_system_prompt(self) -> str:
        return evo_prompts.llm_crossover_system_prompt(self.output_style_guidance)

    def _get_radical_innovation_system_prompt(self) -> str:
        return evo_prompts.radical_innovation_system_prompt(self.output_style_guidance)
