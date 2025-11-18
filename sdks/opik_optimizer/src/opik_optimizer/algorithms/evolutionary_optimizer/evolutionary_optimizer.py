import copy
import json
import logging
import random
from typing import Any, cast
from collections.abc import Callable
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
from opik_optimizer.optimization_result import OptimizationResult
from opik_optimizer.optimizable_agent import OptimizableAgent
from opik_optimizer.mcp_utils.mcp_second_pass import MCPSecondPassCoordinator
from opik_optimizer.mcp_utils.mcp_workflow import (
    MCPExecutionConfig,
    extract_tool_arguments,
)
from opik_optimizer.utils.prompt_segments import extract_prompt_segments

from .mcp import EvolutionaryMCPContext, finalize_mcp_result

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
            creator.create("Individual", list, fitness=fitness_attr)

        logger.debug(
            f"Initialized EvolutionaryOptimizer with model: {model}, MOO_enabled: {self.enable_moo}, "
            f"LLM_Crossover: {self.enable_llm_crossover}, Seed: {self.seed}, "
            f"OutputStyleGuidance: '{self.output_style_guidance[:50]}...', "
            f"population_size: {self.population_size}, num_generations: {self.num_generations}, "
            f"mutation_rate: {self.mutation_rate}, crossover_rate: {self.crossover_rate}"
        )

        # (methods already attached above)
        self._mcp_context: EvolutionaryMCPContext | None = None

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

    def _create_individual_from_prompt(
        self, prompt_candidate: chat_prompt.ChatPrompt
    ) -> Any:
        individual = creator.Individual(prompt_candidate.get_messages())
        setattr(individual, "tools", copy.deepcopy(prompt_candidate.tools))
        setattr(individual, "function_map", prompt_candidate.function_map)
        return individual

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
        prompt: chat_prompt.ChatPrompt,
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
        # offspring = list(map[Any](self.toolbox.clone, offspring))
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
                    initial_prompt=prompt,
                    model=self.model,
                    model_parameters=self.model_parameters,
                    diversity_threshold=DEFAULT_DIVERSITY_THRESHOLD,
                    mcp_context=self._mcp_context,
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
        prompt: chat_prompt.ChatPrompt,
        dataset: opik.Dataset,
        metric: Callable,
        experiment_config: dict | None = None,
        n_samples: int | None = None,
        auto_continue: bool = False,
        agent_class: type[OptimizableAgent] | None = None,
        project_name: str = "Optimization",
        optimization_id: str | None = None,
        max_trials: int = 10,
        mcp_config: MCPExecutionConfig | None = None,
        *args: Any,
        **kwargs: Any,
    ) -> OptimizationResult:
        """
        Args:
            prompt: The prompt to optimize.
            dataset: Dataset used to evaluate each candidate prompt.
            metric: Objective function receiving `(dataset_item, llm_output)`.
            experiment_config: Optional experiment configuration metadata.
            n_samples: Optional number of dataset items to evaluate per prompt.
            auto_continue: Whether to continue automatically after each generation.
            agent_class: Optional agent implementation for executing prompts.
            project_name: Opik project name for logging traces (default: "Optimization").
            optimization_id: Optional ID for the Opik optimization run; when provided it
                must be a valid UUIDv7 string.
            max_trials: Maximum number of prompt evaluations allowed.
            mcp_config: MCP tool-calling configuration (default: None).
        """
        # Use base class validation and setup methods
        self._validate_optimization_inputs(prompt, dataset, metric)
        self.agent_class = self._setup_agent_class(prompt, agent_class)
        evaluation_kwargs: dict[str, Any] = {}
        if mcp_config is not None:
            evaluation_kwargs["mcp_config"] = mcp_config

        # Set project name from parameter
        self.project_name = project_name

        # Step 0. Start Opik optimization run
        opik_optimization_run: optimization.Optimization | None = None
        try:
            opik_optimization_run = self.opik_client.create_optimization(
                dataset_name=dataset.name,
                objective_name=metric.__name__,
                name=self.name,
                metadata=self._build_optimization_config(),
                optimization_id=optimization_id,
            )
            self.current_optimization_id = opik_optimization_run.id
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
            prompt.get_messages(),
            {
                "optimizer": f"{'DEAP MOO' if self.enable_moo else 'DEAP SO'} Evolutionary Optimization",
                "population_size": self.population_size,
                "generations": self.num_generations,
                "mutation_rate": self.mutation_rate,
                "crossover_rate": self.crossover_rate,
            },
            verbose=self.verbose,
            tools=getattr(prompt, "tools", None),
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

        if self.enable_moo:

            def _deap_evaluate_individual_fitness(
                messages: list[dict[str, str]],
            ) -> tuple[float, ...]:
                # Check if we've hit the limit
                if trials_used[0] >= max_trials:
                    logger.debug(
                        f"Skipping evaluation - max_trials ({max_trials}) reached"
                    )
                    return (-float("inf"), float("inf"))  # Worst possible fitness

                trials_used[0] += 1

                primary_fitness_score = evaluation_ops.evaluate_prompt(
                    self,
                    prompt,
                    messages,  # type: ignore
                    dataset=dataset,
                    metric=metric,
                    n_samples=n_samples,
                    experiment_config=(experiment_config or {}).copy(),
                    optimization_id=self.current_optimization_id,
                    verbose=0,
                    **evaluation_kwargs,
                )
                prompt_length = float(len(str(json.dumps(messages))))
                return (primary_fitness_score, prompt_length)

        else:
            # Single-objective
            def _deap_evaluate_individual_fitness(
                messages: list[dict[str, str]],
            ) -> tuple[float, ...]:
                # Check if we've hit the limit
                if trials_used[0] >= max_trials:
                    logger.debug(
                        f"Skipping evaluation - max_trials ({max_trials}) reached"
                    )
                    return (-float("inf"),)  # Worst possible fitness

                trials_used[0] += 1

                fitness_score = evaluation_ops.evaluate_prompt(
                    self,
                    prompt,
                    messages,  # type: ignore
                    dataset=dataset,
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
            initial_eval_result = self._deap_evaluate_individual_fitness(
                prompt.get_messages()
            )  # type: ignore
            initial_primary_score = initial_eval_result[0]
            initial_length = (
                initial_eval_result[1]
                if self.enable_moo
                else float(len(json.dumps(prompt.get_messages())))
            )

            trials_used[0] = 0
            best_primary_score_overall = initial_primary_score
            best_prompt_overall = prompt
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
        initial_prompts: list[chat_prompt.ChatPrompt] = (
            population_ops.initialize_population(
                prompt=prompt,
                output_style_guidance=effective_output_style_guidance,
                mcp_context=self._mcp_context,
                model=self.model,
                model_parameters=self.model_parameters,
                optimization_id=self.current_optimization_id,
                population_size=self.population_size,
                verbose=self.verbose,
            )
        )

        deap_population = [
            self._create_individual_from_prompt(p) for p in initial_prompts
        ]
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
                best_prompt_overall = chat_prompt.ChatPrompt(
                    messages=current_best_for_primary,
                    tools=getattr(current_best_for_primary, "tools", prompt.tools),
                    function_map=getattr(
                        current_best_for_primary, "function_map", prompt.function_map
                    ),
                )
            else:
                # Single-objective
                current_best_on_front = hof[0]
                best_primary_score_overall = current_best_on_front.fitness.values[0]
                best_prompt_overall = chat_prompt.ChatPrompt(
                    messages=current_best_on_front,
                    tools=getattr(current_best_on_front, "tools", prompt.tools),
                    function_map=getattr(
                        current_best_on_front, "function_map", prompt.function_map
                    ),
                )

            if self.enable_moo:
                logger.info(
                    f"Gen {0}: New best primary score: {best_primary_score_overall:.4f}, Prompt: {json.dumps(best_prompt_overall.get_messages())[:100]}..."
                )
            else:
                logger.info(
                    f"Gen {0}: New best score: {best_primary_score_overall:.4f}"
                )

            # Simplified history logging for this transition
            initial_round_data = OptimizationRound(
                round_number=0,
                current_prompt=best_prompt_overall,  # Representative best
                current_score=best_primary_score_overall,
                generated_prompts=[
                    {
                        "prompt": best_prompt_overall,
                        "score": best_primary_score_overall,
                        "trial_scores": [best_primary_score_overall],
                    }
                ],
                best_prompt=best_prompt_overall,
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
                        best_prompt_so_far=best_prompt_overall,
                    )

                # ---------- run one generation --------------------------------
                deap_population, invalid_count = self._run_generation(
                    generation_idx,
                    deap_population,
                    prompt,
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
                # FIXME: Use model.dump() instead of dict()
                gen_round_data = OptimizationRound(
                    round_number=generation_idx,
                    current_prompt=best_prompt_overall,  # Representative best
                    current_score=best_primary_score_overall,
                    generated_prompts=[
                        {"prompt": str(ind), "score": ind.fitness.values[0]}
                        for ind in deap_population
                        if ind.fitness.valid
                    ],
                    best_prompt=best_prompt_overall,
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
                    final_results_log += f"  Solution {i + 1}: Primary Score={sol.fitness.values[0]:.4f}, Length={sol.fitness.values[1]:.0f}, Prompt='{str(sol)[:100]}...'\n"
                best_overall_solution = sorted_hof[0]
                final_best_prompt = chat_prompt.ChatPrompt(
                    messages=best_overall_solution,
                    tools=getattr(best_overall_solution, "tools", prompt.tools),
                    function_map=getattr(
                        best_overall_solution, "function_map", prompt.function_map
                    ),
                )
                final_primary_score = best_overall_solution.fitness.values[0]
                final_length = best_overall_solution.fitness.values[1]
                logger.info(final_results_log)
                logger.info(
                    f"Representative best prompt (highest primary score from Pareto front): '{final_best_prompt}'"
                )
                logger.info(
                    f"  Primary Score ({metric.__name__}): {final_primary_score:.4f}"
                )
                logger.info(f"  Length: {final_length:.0f}")
                final_details.update(
                    {
                        "initial_primary_score": initial_primary_score,
                        "initial_length": initial_length,
                        "final_prompt_representative": final_best_prompt,
                        "final_primary_score_representative": final_primary_score,
                        "final_length_representative": final_length,
                        "pareto_front_solutions": (
                            [
                                {
                                    "prompt": str(ind),
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
                final_best_prompt = best_prompt_overall
                final_primary_score = best_primary_score_overall
                final_length = float(len(json.dumps(final_best_prompt.get_messages())))
                final_details.update(
                    {
                        "initial_primary_score": initial_primary_score,
                        "initial_length": initial_length,
                        "final_prompt_representative": final_best_prompt,
                        "final_primary_score_representative": final_primary_score,
                        "final_length_representative": final_length,
                        "pareto_front_solutions": [],
                    }
                )
        else:
            # Single-objective
            final_best_prompt = best_prompt_overall
            final_primary_score = best_primary_score_overall
            logger.info(f"Final best prompt from Hall of Fame: '{final_best_prompt}'")
            logger.info(
                f"Final best score ({metric.__name__}): {final_primary_score:.4f}"
            )
            final_details.update(
                {
                    "initial_prompt": prompt.get_messages(),
                    "initial_score": initial_primary_score,
                    "initial_score_for_display": initial_primary_score,
                    "final_prompt": final_best_prompt,
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
        reporting.display_result(
            initial_score=initial_score_for_display,
            best_score=final_primary_score,
            best_prompt=final_best_prompt.get_messages(),
            verbose=self.verbose,
            tools=getattr(final_best_prompt, "tools", None),
        )

        final_tools = getattr(final_best_prompt, "tools", None)
        if final_tools:
            final_details["final_tools"] = final_tools
        tool_prompts = self._extract_tool_prompts(final_tools)

        return OptimizationResult(
            optimizer=self.__class__.__name__,
            prompt=final_best_prompt.get_messages(),
            score=final_primary_score,
            initial_prompt=prompt.get_messages(),
            initial_score=initial_primary_score,
            metric_name=metric.__name__,
            details=final_details,
            history=[x.model_dump() for x in self.get_history()],
            llm_calls=self.llm_call_counter,
            tool_calls=self.tool_call_counter,
            dataset_id=dataset.id,
            optimization_id=self.current_optimization_id,
            tool_prompts=tool_prompts,
        )

    def optimize_mcp(
        self,
        prompt: chat_prompt.ChatPrompt,
        dataset: opik.Dataset,
        metric: Callable,
        *,
        tool_name: str,
        second_pass: MCPSecondPassCoordinator,
        experiment_config: dict | None = None,
        n_samples: int | None = None,
        auto_continue: bool = False,
        agent_class: type[OptimizableAgent] | None = None,
        fallback_invoker: Callable[[dict[str, Any]], str] | None = None,
        fallback_arguments: Callable[[Any], dict[str, Any]] | None = None,
        allow_tool_use_on_second_pass: bool = False,
        **kwargs: Any,
    ) -> OptimizationResult:
        if prompt.tools is None or not prompt.tools:
            raise ValueError("Prompt must include tools for MCP optimization")

        panel_style = kwargs.pop("tool_panel_style", "bright_magenta")

        segments = extract_prompt_segments(prompt)
        tool_segment_id = f"tool:{tool_name}"
        segment_lookup = {segment.segment_id: segment for segment in segments}
        if tool_segment_id not in segment_lookup:
            raise ValueError(f"Tool '{tool_name}' not present in prompt tools")

        fallback_args_fn = fallback_arguments or extract_tool_arguments

        if fallback_invoker is None:
            function_map = getattr(prompt, "function_map", {}) or {}
            default_invoker_candidate = function_map.get(tool_name)
            if default_invoker_candidate is not None:
                typed_invoker = cast(Callable[..., str], default_invoker_candidate)

                def _fallback_invoker(args: dict[str, Any]) -> str:
                    return typed_invoker(**args)

                fallback_invoker = _fallback_invoker

        tool_entry = None
        for entry in prompt.tools or []:
            function = entry.get("function", {})
            if (function.get("name") or entry.get("name")) == tool_name:
                tool_entry = entry
                break
        if tool_entry is None:
            raise ValueError(f"Tool '{tool_name}' not present in prompt.tools")

        original_description = tool_entry.get("function", {}).get("description", "")
        tool_metadata = segment_lookup[tool_segment_id].metadata.get("raw_tool", {})

        mcp_config = MCPExecutionConfig(
            coordinator=second_pass,
            tool_name=tool_name,
            fallback_arguments=fallback_args_fn,
            fallback_invoker=fallback_invoker,
            allow_tool_use_on_second_pass=allow_tool_use_on_second_pass,
        )

        previous_context = getattr(self, "_mcp_context", None)
        previous_crossover = self.enable_llm_crossover

        context = EvolutionaryMCPContext(
            tool_name=tool_name,
            tool_segment_id=tool_segment_id,
            original_description=original_description,
            tool_metadata=tool_metadata,
            panel_style=panel_style,
        )

        self._mcp_context = context
        self.enable_llm_crossover = False

        try:
            result = self.optimize_prompt(
                prompt=prompt,
                dataset=dataset,
                metric=metric,
                experiment_config=experiment_config,
                n_samples=n_samples,
                auto_continue=auto_continue,
                agent_class=agent_class,
                mcp_config=mcp_config,
                **kwargs,
            )
        finally:
            self._mcp_context = previous_context
            self.enable_llm_crossover = previous_crossover

        finalize_mcp_result(result, context, panel_style, optimizer=self)
        return result

    # Evaluation is provided by EvaluationOps

    # LLM crossover is provided by CrossoverOps
    # Helper provided by Helpers

    # Override prompt builders to centralize strings in prompts.py
    def _get_reasoning_system_prompt_for_variation(self) -> str:
        return evo_prompts.variation_system_prompt(self.output_style_guidance)

    def _get_llm_crossover_system_prompt(self) -> str:
        return evo_prompts.llm_crossover_system_prompt(self.output_style_guidance)

    def _get_radical_innovation_system_prompt(self) -> str:
        return evo_prompts.radical_innovation_system_prompt(self.output_style_guidance)
