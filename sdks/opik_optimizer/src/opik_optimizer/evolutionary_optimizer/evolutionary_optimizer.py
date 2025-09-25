import json
import logging
import random
from typing import Any, cast, TYPE_CHECKING
from collections.abc import Callable
import sys
import warnings

import rapidfuzz.distance.Indel
import numpy as np
import opik

# DEAP imports
from deap import base, tools
from deap import creator as _creator
from opik.api_objects import optimization
from opik.environment import get_tqdm_for_current_environment

from opik_optimizer.base_optimizer import BaseOptimizer, OptimizationRound
from opik_optimizer.optimization_config import chat_prompt
from opik_optimizer.optimization_result import OptimizationResult
from opik_optimizer.optimizable_agent import OptimizableAgent

from . import reporting
from .llm_support import LlmSupport
from .mutation_ops import MutationOps
from .crossover_ops import CrossoverOps
from .population_ops import PopulationOps
from .evaluation_ops import EvaluationOps
from .helpers import Helpers
from .style_ops import StyleOps
from . import prompts as evo_prompts

logger = logging.getLogger(__name__)
tqdm = get_tqdm_for_current_environment()

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
    DEFAULT_OUTPUT_STYLE_GUIDANCE = (
        "Produce clear, effective, and high-quality responses suitable for the task."
    )
    DEFAULT_MOO_WEIGHTS = (1.0, -1.0)  # (Maximize Score, Minimize Length)

    # Prompt constants moved into prompts.py
    if TYPE_CHECKING:
        _llm_deap_crossover: Any
        _deap_crossover: Any
        _deap_mutation: Any
        _initialize_population: Any
        _evaluate_prompt: Any
        _infer_output_style_from_dataset: Any

    def __init__(
        self,
        model: str,
        population_size: int = DEFAULT_POPULATION_SIZE,
        num_generations: int = DEFAULT_NUM_GENERATIONS,
        mutation_rate: float = DEFAULT_MUTATION_RATE,
        crossover_rate: float = DEFAULT_CROSSOVER_RATE,
        tournament_size: int = DEFAULT_TOURNAMENT_SIZE,
        num_threads: int | None = None,
        elitism_size: int = DEFAULT_ELITISM_SIZE,
        adaptive_mutation: bool = DEFAULT_ADAPTIVE_MUTATION,
        enable_moo: bool = DEFAULT_ENABLE_MOO,
        enable_llm_crossover: bool = DEFAULT_ENABLE_LLM_CROSSOVER,
        seed: int | None = DEFAULT_SEED,
        output_style_guidance: str | None = None,
        infer_output_style: bool = False,
        verbose: int = 1,
        n_threads: int = DEFAULT_NUM_THREADS,
        **model_kwargs: Any,
    ) -> None:
        """
        Args:
            model: The model to use for evaluation
            population_size: Number of prompts in the population
            num_generations: Number of generations to run
            mutation_rate: Mutation rate for genetic operations
            crossover_rate: Crossover rate for genetic operations
            tournament_size: Tournament size for selection
            n_threads: Number of threads for parallel evaluation
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
        if sys.version_info >= (3, 13):
            warnings.warn(
                "Python 3.13 is not officially supported (python_requires <3.13). "
                "You may see asyncio teardown warnings. Prefer Python 3.12.",
                RuntimeWarning,
            )
        if "project_name" in model_kwargs:
            print(
                "Removing `project_name` from constructor; it now belongs in the ChatPrompt()"
            )
            del model_kwargs["project_name"]

        super().__init__(model=model, verbose=verbose, **model_kwargs)
        self.population_size = population_size
        self.num_generations = num_generations
        self.mutation_rate = mutation_rate
        self.crossover_rate = crossover_rate
        self.tournament_size = tournament_size
        if num_threads is not None:
            print("num_threads is deprecated; use n_threads instead")
            n_threads = num_threads
        self.num_threads = n_threads
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
        self._current_optimization_id: str | None = None
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

        self.toolbox = base.Toolbox()
        # Attach methods from helper mixin modules to this instance to avoid
        # multiple inheritance while preserving behavior.
        self._attach_helper_methods()
        self.toolbox.register(
            "default_individual", lambda: creator.Individual("placeholder")
        )
        self.toolbox.register(
            "population", tools.initRepeat, list, self.toolbox.default_individual
        )

        if self.enable_llm_crossover:
            self.toolbox.register("mate", self._llm_deap_crossover)
        else:
            self.toolbox.register("mate", self._deap_crossover)

        self.toolbox.register("mutate", self._deap_mutation)

        if self.enable_moo:
            self.toolbox.register("select", tools.selNSGA2)
        else:
            self.toolbox.register(
                "select", tools.selTournament, tournsize=self.tournament_size
            )

        logger.debug(
            f"Initialized EvolutionaryOptimizer with model: {model}, MOO_enabled: {self.enable_moo}, "
            f"LLM_Crossover: {self.enable_llm_crossover}, Seed: {self.seed}, "
            f"OutputStyleGuidance: '{self.output_style_guidance[:50]}...', "
            f"population_size: {self.population_size}, num_generations: {self.num_generations}, "
            f"mutation_rate: {self.mutation_rate}, crossover_rate: {self.crossover_rate}"
        )

        # (methods already attached above)

    def _attach_helper_methods(self) -> None:
        """Bind selected methods from mixin modules onto this instance."""

        def bind(cls: Any, names: list[str]) -> None:
            for name in names:
                func = getattr(cls, name)
                setattr(self, name, func.__get__(self, self.__class__))

        # LLM calls
        bind(LlmSupport, ["_call_model"])

        # Mutations
        bind(
            MutationOps,
            [
                "_deap_mutation",
                "_semantic_mutation",
                "_structural_mutation",
                "_word_level_mutation_prompt",
                "_word_level_mutation",
                "_get_synonym",
                "_modify_phrase",
                "_radical_innovation_mutation",
            ],
        )

        # Crossover
        bind(
            CrossoverOps,
            [
                "_deap_crossover_chunking_strategy",
                "_deap_crossover_word_level",
                "_deap_crossover",
                "_llm_deap_crossover",
                "_extract_json_arrays",
            ],
        )

        # Population management
        bind(
            PopulationOps,
            [
                "_initialize_population",
                "_should_restart_population",
                "_restart_population",
            ],
        )

        # Evaluation
        bind(EvaluationOps, ["_evaluate_prompt"])

        # Helpers
        bind(Helpers, ["_get_task_description_for_llm"])

        # Style inference
        bind(StyleOps, ["_infer_output_style_from_dataset"])

    def _get_adaptive_mutation_rate(self) -> float:
        """Calculate adaptive mutation rate based on population diversity and progress."""
        if not self.adaptive_mutation or len(self._best_fitness_history) < 2:
            return self.mutation_rate

        # Calculate improvement rate
        recent_improvement = (
            self._best_fitness_history[-1] - self._best_fitness_history[-2]
        ) / abs(self._best_fitness_history[-2])

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
        elif (
            recent_improvement < 0.01
            and current_diversity < self.DEFAULT_DIVERSITY_THRESHOLD
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

    def _calculate_population_diversity(self) -> float:
        """Calculate the diversity of the current population."""
        if not hasattr(self, "_current_population") or not self._current_population:
            return 0.0

        # Calculate average Levenshtein using rapidfuzz distance between all pairs
        total_distance = 0.0
        count = 0
        for i in range(len(self._current_population)):
            for j in range(i + 1, len(self._current_population)):
                str1 = str(self._current_population[i])
                str2 = str(self._current_population[j])
                distance = rapidfuzz.distance.Indel.normalized_similarity(str1, str2)
                max_len = max(len(str1), len(str2))
                if max_len > 0:
                    normalized_distance = distance / max_len
                    total_distance += normalized_distance
                    count += 1

        return total_distance / count if count > 0 else 0.0

    # Mutations and helpers are implemented in mixins.

    def _should_restart_population(self, curr_best: float) -> bool:
        """
        Update internal counters and decide if we should trigger
        a population restart based on lack of improvement.
        """
        if self._best_primary_score_history:
            threshold = self._best_primary_score_history[-1] * (
                1 + self.DEFAULT_RESTART_THRESHOLD
            )
            if curr_best < threshold:
                self._gens_since_pop_improvement += 1
            else:
                self._gens_since_pop_improvement = 0
        self._best_primary_score_history.append(curr_best)
        return self._gens_since_pop_improvement >= self.DEFAULT_RESTART_GENERATIONS

    def _restart_population(
        self,
        hof: tools.HallOfFame,
        population: list[Any],
        best_prompt_so_far: chat_prompt.ChatPrompt,
    ) -> list[Any]:
        """Return a fresh, evaluated population seeded by elites."""
        if self.enable_moo:
            elites = list(hof)
        else:
            elites = tools.selBest(population, self.elitism_size)

        seed_prompt = (
            chat_prompt.ChatPrompt(
                messages=max(elites, key=lambda x: x.fitness.values[0])
            )
            if elites
            else best_prompt_so_far
        )

        prompt_variants = self._initialize_population(seed_prompt)
        new_pop = [creator.Individual(p.get_messages()) for p in prompt_variants]

        for ind, fit in zip(new_pop, map(self.toolbox.evaluate, new_pop)):
            ind.fitness.values = fit

        self._gens_since_pop_improvement = 0
        return new_pop

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
            offspring = self.toolbox.select(population, self.population_size)
        else:
            elites = tools.selBest(population, self.elitism_size)
            rest = self.toolbox.select(population, len(population) - self.elitism_size)
            offspring = elites + rest

        # --- crossover -------------------------------------------------
        report.performing_crossover()
        offspring = list(map(self.toolbox.clone, offspring))
        for i in range(0, len(offspring), 2):
            if i + 1 < len(offspring):
                c1, c2 = offspring[i], offspring[i + 1]
                if random.random() < self.crossover_rate:
                    c1_new, c2_new = self.toolbox.mate(c1, c2)
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
                new_ind = self.toolbox.mutate(ind, initial_prompt=prompt)
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
            fit = self.toolbox.evaluate(ind)
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
        **kwargs: Any,
    ) -> OptimizationResult:
        """
        Args:
            prompt: The prompt to optimize
            dataset: The dataset to use for evaluation
            metric: Metric function to optimize with, should have the arguments `dataset_item` and `llm_output`
            experiment_config: Optional experiment configuration
            n_samples: Optional number of samples to use
            auto_continue: Whether to automatically continue optimization
            agent_class: Optional agent class to use
            **kwargs: Additional keyword arguments including:
                mcp_config (MCPExecutionConfig | None): MCP tool calling configuration (default: None)
        """
        # Use base class validation and setup methods
        self.validate_optimization_inputs(prompt, dataset, metric)
        self.configure_prompt_model(prompt)
        self.agent_class = self.setup_agent_class(prompt, agent_class)

        # Extract MCP config from kwargs
        mcp_config = kwargs.pop("mcp_config", None)

        self.project_name = self.agent_class.project_name

        # Step 0. Start Opik optimization run
        opik_optimization_run: optimization.Optimization | None = None
        try:
            opik_optimization_run = self.opik_client.create_optimization(
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
        self.reset_counters()  # Reset counters for run
        self._history: list[OptimizationRound] = []
        self._current_generation = 0
        self._best_fitness_history = []
        self._generations_without_improvement = 0
        self._current_population = []
        self._generations_without_overall_improvement = 0

        if self.enable_moo:

            def _deap_evaluate_individual_fitness(
                messages: list[dict[str, str]],
            ) -> tuple[float, float]:
                primary_fitness_score: float = self._evaluate_prompt(
                    prompt,
                    messages,  # type: ignore
                    dataset=dataset,
                    metric=metric,
                    n_samples=n_samples,
                    experiment_config=(experiment_config or {}).copy(),
                    optimization_id=self._current_optimization_id,
                    verbose=0,
                )
                prompt_length = float(len(str(json.dumps(messages))))
                return (primary_fitness_score, prompt_length)

        else:
            # Single-objective
            def _deap_evaluate_individual_fitness(
                messages: list[dict[str, str]],
            ) -> tuple[float, float]:
                fitness_score: float = self._evaluate_prompt(
                    prompt,
                    messages,  # type: ignore
                    dataset=dataset,
                    metric=metric,
                    n_samples=n_samples,
                    experiment_config=(experiment_config or {}).copy(),
                    optimization_id=self._current_optimization_id,
                    verbose=0,
                )
                return (fitness_score, 0.0)

        self.toolbox.register("evaluate", _deap_evaluate_individual_fitness)

        # Step 2. Compute the initial performance of the prompt
        with reporting.baseline_performance(
            verbose=self.verbose
        ) as report_baseline_performance:
            initial_eval_result = _deap_evaluate_individual_fitness(
                prompt.get_messages()
            )  # type: ignore
            initial_primary_score = initial_eval_result[0]
            initial_length = (
                initial_eval_result[1]
                if self.enable_moo
                else float(len(json.dumps(prompt.get_messages())))
            )

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
            inferred_style = self._infer_output_style_from_dataset(dataset, prompt)
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
        initial_prompts: list[chat_prompt.ChatPrompt] = self._initialize_population(
            prompt=prompt
        )

        deap_population = [
            creator.Individual(p.get_messages()) for p in initial_prompts
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
            fitnesses: list[Any] = list(map(self.toolbox.evaluate, deap_population))
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
                    messages=current_best_for_primary
                )
            else:
                # Single-objective
                current_best_on_front = hof[0]
                best_primary_score_overall = current_best_on_front.fitness.values[0]
                best_prompt_overall = chat_prompt.ChatPrompt(
                    messages=current_best_on_front
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
                report_evolutionary_algo.start_gen(generation_idx, self.num_generations)

                curr_best_score = self._population_best_score(deap_population)

                # ---------- restart logic -------------------------------------
                if self._should_restart_population(curr_best_score):
                    report_evolutionary_algo.restart_population(
                        self.DEFAULT_RESTART_GENERATIONS
                    )
                    deap_population = self._restart_population(
                        hof, deap_population, best_prompt_overall
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
                    messages=best_overall_solution
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
        if opik_optimization_run:
            try:
                opik_optimization_run.update(status="completed")
                logger.info(
                    f"Opik Optimization run {self._current_optimization_id} status updated to completed."
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
                "temperature": self.model_kwargs.get("temperature"),
                "stopped_early": stopped_early_flag,
                "rounds": self.get_history(),
                "user_output_style_guidance": self.output_style_guidance,
                "infer_output_style_requested": self.infer_output_style,
                "final_effective_output_style_guidance": effective_output_style_guidance,
                "infer_output_style": self.infer_output_style,
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
            optimization_id=self._current_optimization_id,
        )

    # Evaluation is provided by EvaluationOps

    # LLM crossover is provided by CrossoverOps
    # Helper provided by Helpers

    # Override prompt builders to centralize strings in prompts.py
    def _get_reasoning_system_prompt_for_variation(self) -> str:
        return evo_prompts.variation_system_prompt(self.output_style_guidance)

    def get_llm_crossover_system_prompt(self) -> str:
        return evo_prompts.llm_crossover_system_prompt(self.output_style_guidance)

    def _get_radical_innovation_system_prompt(self) -> str:
        return evo_prompts.radical_innovation_system_prompt(self.output_style_guidance)
