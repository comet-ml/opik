import copy
import json
import logging
import random
from typing import Any, cast
from collections.abc import Callable
import sys
import warnings

import numpy as np

# DEAP imports
try:
    from deap import base, tools
    from deap import creator as _creator

    _DEAP_IMPORT_ERROR: Exception | None = None
except Exception as exc:  # pragma: no cover - exercised when DEAP is missing
    base = None
    tools = None
    _creator = None
    _DEAP_IMPORT_ERROR = exc

from opik_optimizer.base_optimizer import BaseOptimizer
from opik_optimizer.core.state import AlgorithmResult, OptimizationContext
from opik_optimizer.utils.prompt_library import PromptOverrides
from opik_optimizer import constants
from ...api_objects import chat_prompt

from . import prompts as evo_prompts
from .ops import generation_ops, mutation_ops, result_ops

logger = logging.getLogger(__name__)
creator = cast(Any, _creator)  # type: ignore[assignment]


class EvolutionaryOptimizer(BaseOptimizer):
    supports_tool_optimization: bool = True
    supports_prompt_optimization: bool = True
    supports_multimodal: bool = True
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
        enable_semantic_crossover: Whether to use semantic crossover before standard LLM crossover
        output_style_guidance: Optional guidance for output style in generated prompts
        infer_output_style: Whether to automatically infer output style from the dataset
        n_threads: Number of threads for parallel evaluation
        verbose: Controls internal logging/progress bars (0=off, 1=on)
        seed: Random seed for reproducibility
        prompt_overrides: Optional dict or callable to customize internal prompts.
            Dict: {"prompt_key": "new_template"} to override specific prompts.
            Callable: function(prompts: PromptLibrary) -> None to modify prompts programmatically.
    """

    DEFAULT_EARLY_STOPPING_GENERATIONS = (
        constants.EVOLUTIONARY_DEFAULT_EARLY_STOPPING_GENERATIONS
    )
    DEFAULT_OUTPUT_STYLE_GUIDANCE = constants.EVOLUTIONARY_DEFAULT_OUTPUT_STYLE_GUIDANCE
    DEFAULT_MOO_WEIGHTS = constants.EVOLUTIONARY_DEFAULT_MOO_WEIGHTS
    DEFAULT_HALL_OF_FAME_SIZE = constants.EVOLUTIONARY_DEFAULT_HALL_OF_FAME_SIZE
    DEFAULT_MIN_MUTATION_RATE = constants.EVOLUTIONARY_DEFAULT_MIN_MUTATION_RATE
    DEFAULT_MAX_MUTATION_RATE = constants.EVOLUTIONARY_DEFAULT_MAX_MUTATION_RATE
    DEFAULT_RESTART_THRESHOLD = constants.EVOLUTIONARY_DEFAULT_RESTART_THRESHOLD
    DEFAULT_RESTART_GENERATIONS = constants.EVOLUTIONARY_DEFAULT_RESTART_GENERATIONS
    DEFAULT_DIVERSITY_THRESHOLD = constants.EVOLUTIONARY_DEFAULT_DIVERSITY_THRESHOLD
    DEFAULT_ENABLE_SEMANTIC_CROSSOVER = (
        constants.EVOLUTIONARY_DEFAULT_ENABLE_SEMANTIC_CROSSOVER
    )

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
        "semantic_crossover_system_prompt_template": evo_prompts.SEMANTIC_CROSSOVER_SYSTEM_PROMPT_TEMPLATE,
        "semantic_crossover_user_prompt_template": evo_prompts.SEMANTIC_CROSSOVER_USER_PROMPT_TEMPLATE,
        "radical_innovation_system_prompt_template": evo_prompts.RADICAL_INNOVATION_SYSTEM_PROMPT_TEMPLATE,
        "radical_innovation_user_prompt_template": evo_prompts.RADICAL_INNOVATION_USER_PROMPT_TEMPLATE,
        "mutation_strategy_rephrase": evo_prompts.MUTATION_STRATEGY_REPHRASE,
        "mutation_strategy_simplify": evo_prompts.MUTATION_STRATEGY_SIMPLIFY,
        "mutation_strategy_elaborate": evo_prompts.MUTATION_STRATEGY_ELABORATE,
        "mutation_strategy_restructure": evo_prompts.MUTATION_STRATEGY_RESTRUCTURE,
        "mutation_strategy_focus": evo_prompts.MUTATION_STRATEGY_FOCUS,
        "mutation_strategy_increase_complexity_and_detail": evo_prompts.MUTATION_STRATEGY_INCREASE_COMPLEXITY,
    }

    def pre_optimize(self, context: OptimizationContext) -> None:
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
            "semantic_crossover": self.enable_semantic_crossover,
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
            "enable_semantic_crossover": self.enable_semantic_crossover,
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

    def _ensure_deap_available(self) -> None:
        if base is None or tools is None or _creator is None:
            raise RuntimeError(
                "DEAP is required for EvolutionaryOptimizer. "
                "Install the optimizer extras that include DEAP."
            ) from _DEAP_IMPORT_ERROR

    def _seed_rngs(self) -> None:
        """
        Seed random number generators used by this optimizer.

        DEAP's built-in operators rely on Python's `random` module, while some
        surrounding code (and potential user extensions) may use NumPy RNG.
        """
        if self.seed is None:
            return
        random.seed(self.seed)
        np.random.seed(self.seed)

    def __init__(
        self,
        model: str = constants.DEFAULT_MODEL,
        model_parameters: dict[str, Any] | None = None,
        population_size: int = constants.EVOLUTIONARY_DEFAULT_POPULATION_SIZE,
        num_generations: int = constants.EVOLUTIONARY_DEFAULT_NUM_GENERATIONS,
        mutation_rate: float = constants.EVOLUTIONARY_DEFAULT_MUTATION_RATE,
        crossover_rate: float = constants.EVOLUTIONARY_DEFAULT_CROSSOVER_RATE,
        tournament_size: int = constants.EVOLUTIONARY_DEFAULT_TOURNAMENT_SIZE,
        elitism_size: int = constants.EVOLUTIONARY_DEFAULT_ELITISM_SIZE,
        adaptive_mutation: bool = constants.EVOLUTIONARY_DEFAULT_ADAPTIVE_MUTATION,
        enable_moo: bool = constants.EVOLUTIONARY_DEFAULT_ENABLE_MOO,
        enable_llm_crossover: bool = constants.EVOLUTIONARY_DEFAULT_ENABLE_LLM_CROSSOVER,
        enable_semantic_crossover: bool = (
            constants.EVOLUTIONARY_DEFAULT_ENABLE_SEMANTIC_CROSSOVER
        ),
        output_style_guidance: str | None = None,
        infer_output_style: bool = False,
        n_threads: int = constants.DEFAULT_NUM_THREADS,
        verbose: int = 1,
        seed: int = constants.DEFAULT_SEED,
        name: str | None = None,
        prompt_overrides: PromptOverrides = None,
        skip_perfect_score: bool = constants.DEFAULT_SKIP_PERFECT_SCORE,
        perfect_score: float = constants.DEFAULT_PERFECT_SCORE,
    ) -> None:
        # Initialize base class first
        if sys.version_info >= (3, 13):
            warnings.warn(
                "Python 3.13 is not officially supported (python_requires <3.13). "
                "You may see asyncio teardown warnings. Prefer Python 3.12.",
                RuntimeWarning,
            )

        self._ensure_deap_available()

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
        self.enable_semantic_crossover = enable_semantic_crossover
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
        self._current_generation_idx: int | None = None
        self._deap_evaluate_individual_fitness: Callable[[Any], tuple[float, ...]] = (
            lambda _ind: (0.0,)
        )

        if self.seed is not None:
            self._seed_rngs()
            logger.info(f"Global random seed set to: {self.seed}")
            # Note: DEAP operators use Python's `random`, so seeding `random`
            # (and NumPy for any NumPy-based operations) is sufficient here.

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
        mutation_rate, generations_without_improvement = (
            mutation_ops.compute_adaptive_mutation_rate(
                current_rate=self.mutation_rate,
                best_fitness_history=self._best_fitness_history,
                current_population=self._current_population,
                generations_without_improvement=self._generations_without_improvement,
                adaptive_mutation=self.adaptive_mutation,
                restart_threshold=self.DEFAULT_RESTART_THRESHOLD,
                restart_generations=self.DEFAULT_RESTART_GENERATIONS,
                min_rate=self.DEFAULT_MIN_MUTATION_RATE,
                max_rate=self.DEFAULT_MAX_MUTATION_RATE,
                diversity_threshold=self.DEFAULT_DIVERSITY_THRESHOLD,
            )
        )
        self._generations_without_improvement = generations_without_improvement
        return mutation_rate

    def _run_generation(
        self,
        generation_idx: int,
        population: list[Any],
        initial_prompts: dict[str, chat_prompt.ChatPrompt],
        hof: tools.HallOfFame,
        best_primary_score_overall: float,
    ) -> tuple[list[Any], int]:
        return generation_ops.run_generation(
            self,
            generation_idx=generation_idx,
            population=population,
            initial_prompts=initial_prompts,
            hof=hof,
            best_primary_score_overall=best_primary_score_overall,
        )

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
        self._ensure_deap_available()
        # Ensure deterministic behavior per optimization run (important when
        # reusing the same optimizer instance across multiple runs).
        self._seed_rngs()
        optimizable_prompts = context.prompts
        experiment_config = context.experiment_config
        max_trials = context.max_trials
        self._optimize_tools = context.extra_params.get("optimize_tools")
        self._tool_names = context.extra_params.get("tool_names")
        self._evaluation_metric = context.metric

        # Initialize progress tracking for display
        self._current_round = 0
        self._total_rounds = self.num_generations

        self._history_builder.clear()
        self._best_fitness_history = []
        self._generations_without_improvement = 0
        self._current_population = []
        self._generations_without_overall_improvement = 0

        generation_ops.build_deap_evaluator(
            self, context=context, experiment_config=experiment_config
        )

        # Use baseline score from context (computed by base class)
        initial_primary_score = cast(float, context.baseline_score)
        initial_prompts_messages = {
            name: p.get_messages() for name, p in optimizable_prompts.items()
        }
        initial_length = float(len(json.dumps(initial_prompts_messages)))

        best_primary_score_overall = initial_primary_score
        best_prompts_overall = optimizable_prompts

        effective_output_style_guidance = generation_ops.resolve_output_style_guidance(
            self, dataset=context.dataset
        )

        deap_population = generation_ops.initialize_population(
            self,
            optimizable_prompts=optimizable_prompts,
            output_style_guidance=effective_output_style_guidance,
        )

        # Step 5. Initialize the hall of fame (Pareto front for MOO) and stats for MOO or SO
        if self.enable_moo:
            hof = tools.ParetoFront()
        else:
            # Single-objective
            hof = tools.HallOfFame(self.DEFAULT_HALL_OF_FAME_SIZE)

        best_primary_score_overall, best_prompts_overall = (
            generation_ops.evaluate_initial_population(
                self,
                context=context,
                deap_population=deap_population,
                hof=hof,
                best_primary_score_overall=best_primary_score_overall,
                best_prompts_overall=best_prompts_overall,
            )
        )

        dataset_split = context.dataset_split or (
            "validation" if context.validation_dataset is not None else "train"
        )
        self.set_default_dataset_split(dataset_split)
        context.dataset_split = dataset_split
        round_handle = self.pre_round(context)
        generation_ops.post_population_round(
            self,
            context=context,
            population=deap_population,
            hof=hof,
            generation_idx=0,
            round_handle=round_handle,
            initial_primary_score=initial_primary_score,
            best_primary_score_overall=best_primary_score_overall,
            best_prompts_overall=best_prompts_overall,
            use_valid_index=False,
        )

        (
            deap_population,
            best_primary_score_overall,
            best_prompts_overall,
            generation_idx,
        ) = generation_ops.run_generations(
            self,
            context=context,
            deap_population=deap_population,
            hof=hof,
            optimizable_prompts=optimizable_prompts,
            best_primary_score_overall=best_primary_score_overall,
            best_prompts_overall=best_prompts_overall,
            initial_primary_score=initial_primary_score,
            max_trials=max_trials,
        )

        return result_ops.build_algorithm_result(
            self,
            context=context,
            optimizable_prompts=optimizable_prompts,
            best_prompts_overall=best_prompts_overall,
            best_primary_score_overall=best_primary_score_overall,
            initial_primary_score=initial_primary_score,
            initial_length=initial_length,
            hof=hof,
            effective_output_style_guidance=effective_output_style_guidance,
            generation_idx=generation_idx,
        )
