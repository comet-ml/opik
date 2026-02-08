import logging
from typing import Any, cast

from opik import Dataset
from ...base_optimizer import BaseOptimizer
from ...core.state import AlgorithmResult, OptimizationContext
from ...utils.prompt_library import PromptOverrides
from ... import constants
from ...api_objects.types import MetricFunction
from ...utils import throttle as _throttle
from ...utils.logging import debug_log
from collections.abc import Sequence
from .ops.halloffame_ops import PromptHallOfFame, add_best_candidate_to_hof
from ...core.results import OptimizationRound

# Import ops modules
from .ops import (
    candidate_ops,
    context_learning_ops,
    evaluation_ops,
    history_ops,
    halloffame_ops,
    result_ops,
)
from . import prompts as meta_prompts

# Set up logging
logger = logging.getLogger(__name__)  # Gets logger configured by setup_logging

_rate_limiter = _throttle.get_rate_limiter_for_current_opik_installation()


class MetaPromptOptimizer(BaseOptimizer):
    supports_tool_optimization: bool = True
    supports_prompt_optimization: bool = True
    supports_multimodal: bool = True
    """
    Meta-Prompt Optimizer that uses LLM-based meta-reasoning to iteratively improve prompts.

    This optimizer uses an LLM to analyze prompt performance and generate improved variations
    by reasoning about what changes would be most effective. It's particularly useful for:
    - Ensuring prompts follow best practices
    - Refining prompts for clarity and effectiveness
    - Optimizing prompts for specific evaluation metrics

    The optimizer works by:
    1. Evaluating the current prompt on a dataset
    2. Using an LLM to reason about improvements based on performance
    3. Generating candidate prompt variations
    4. Evaluating candidates and selecting the best
    5. Repeating until max_trials is reached or performance plateaus

    Context learning (dataset example stuffing) and Hall-of-Fame pattern mining are
    implemented as separate ops modules to enable extraction into extensions later.

    Args:
        model: LiteLLM model name for optimizer's internal reasoning/generation calls
        model_parameters: Optional dict of LiteLLM parameters for optimizer's internal LLM calls.
            Common params: temperature, max_tokens, max_completion_tokens, top_p.
            See: https://docs.litellm.ai/docs/completion/input
        prompts_per_round: Number of candidate prompts to generate per optimization round
        enable_context: Whether to include task-specific context learning when reasoning
        num_task_examples: Number of dataset examples to show in task context (default: 10)
        task_context_columns: Specific dataset columns to include in context (None = all input columns)
        n_threads: Number of parallel threads for prompt evaluation
        verbose: Controls internal logging/progress bars (0=off, 1=on)
        seed: Random seed for reproducibility
        use_hall_of_fame: Enable Hall of Fame pattern extraction and re-injection
        prettymode_prompt_history: (removed) history formatting follows display settings
        prompt_overrides: Optional dict or callable to customize internal prompts.
            Dict: {"prompt_key": "new_template"} to override specific prompts.
            Callable: function(prompts: PromptLibrary) -> None to modify prompts programmatically.
    """

    # Prompt templates for this optimizer
    # Keys match what ops files expect (e.g., optimizer.get_prompt("reasoning_system"))
    DEFAULT_PROMPTS: dict[str, str] = {
        "reasoning_system": meta_prompts.REASONING_SYSTEM_PROMPT_TEMPLATE,
        "candidate_generation": meta_prompts.CANDIDATE_GENERATION_USER_PROMPT_TEMPLATE,
        "agent_bundle_user": meta_prompts.AGENT_BUNDLE_USER_PROMPT_TEMPLATE,
        "pattern_extraction_system": meta_prompts.PATTERN_EXTRACTION_SYSTEM_PROMPT_TEMPLATE,
        "pattern_extraction_user": meta_prompts.PATTERN_EXTRACTION_USER_PROMPT_TEMPLATE,
        "synthesis": meta_prompts.SYNTHESIS_PROMPT_TEMPLATE,
        "tool_description_system": meta_prompts.TOOL_DESCRIPTION_SYSTEM_PROMPT_TEMPLATE,
        "tool_description_user": meta_prompts.TOOL_DESCRIPTION_USER_PROMPT_TEMPLATE,
    }

    def __init__(
        self,
        model: str = constants.DEFAULT_MODEL,
        model_parameters: dict[str, Any] | None = None,
        prompts_per_round: int = constants.META_PROMPT_DEFAULT_PROMPTS_PER_ROUND,
        enable_context: bool = constants.DEFAULT_ENABLE_CONTEXT_LEARNING,
        num_task_examples: int = constants.META_PROMPT_DEFAULT_NUM_TASK_EXAMPLES,
        task_context_columns: list[str] | None = None,
        n_threads: int = constants.DEFAULT_NUM_THREADS,
        verbose: int = 1,
        seed: int = constants.DEFAULT_SEED,
        name: str | None = None,
        use_hall_of_fame: bool = True,
        prompt_overrides: PromptOverrides = None,
        skip_perfect_score: bool = constants.DEFAULT_SKIP_PERFECT_SCORE,
        perfect_score: float = constants.DEFAULT_PERFECT_SCORE,
    ) -> None:
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
        self.prompts_per_round = prompts_per_round
        self.synthesis_prompts_per_round = (
            constants.META_PROMPT_DEFAULT_SYNTHESIS_PROMPTS_PER_ROUND
        )
        self.synthesis_start_round = constants.META_PROMPT_DEFAULT_SYNTHESIS_START_ROUND
        self.synthesis_round_interval = (
            constants.META_PROMPT_DEFAULT_SYNTHESIS_ROUND_INTERVAL
        )
        self.n_threads = n_threads
        self.dataset: Dataset | None = None
        self.enable_context = enable_context
        self.num_task_examples = num_task_examples
        self.task_context_columns = task_context_columns
        self.selection_strategy = "best_by_metric"

        # TODO: Extract context learning into an extension module shared across optimizers.
        # Calculate token budget for task context data stuffing (dataset examples only).
        # This is ONLY used for adaptive fitting logic in get_task_context().
        self.max_context_tokens = context_learning_ops.calculate_max_context_tokens(
            self.model
        )

        # TODO: Extract Hall of Fame into an extension module shared across optimizers.
        # Hall of Fame for pattern mining.
        self.use_hall_of_fame = use_hall_of_fame
        self.extract_metric_understanding = (
            constants.META_PROMPT_DEFAULT_EXTRACT_METRIC_UNDERSTANDING
        )
        self.allow_user_prompt_optimization = (
            constants.META_PROMPT_DEFAULT_ALLOW_USER_PROMPT_OPTIMIZATION
        )
        self.hall_of_fame_size = constants.META_PROMPT_DEFAULT_HALL_OF_FAME_SIZE
        self.pattern_extraction_interval = (
            constants.META_PROMPT_DEFAULT_HALL_OF_FAME_PATTERN_EXTRACTION_INTERVAL
        )
        self.pattern_injection_rate = (
            constants.META_PROMPT_DEFAULT_HALL_OF_FAME_PATTERN_INJECTION_RATE
        )

        self.hall_of_fame: PromptHallOfFame | None = None
        if self.use_hall_of_fame:
            self.hall_of_fame = PromptHallOfFame(
                max_size=self.hall_of_fame_size,
                pattern_extraction_interval=self.pattern_extraction_interval,
                prompts=self._prompts,
            )
            logger.debug(
                f"Hall of Fame enabled: size={self.hall_of_fame_size}, "
                f"extraction_interval={self.pattern_extraction_interval}"
            )

        logger.debug(f"Initialized MetaPromptOptimizer with model={model}")
        logger.debug(f"Prompts/round: {prompts_per_round}")

        # Initialize progress tracking
        self._current_round = 0
        self._total_rounds = 0
        self._current_candidate = 0
        self._total_candidates_in_round = 0

    def get_metadata(self, context: OptimizationContext) -> dict[str, Any]:
        """
        Return MetaPrompt-specific metadata for the optimization result.

        Provides algorithm-specific configuration that can be used in any scenario
        (early stop, completion, etc.). Trial/round counts come from context.
        """
        return {
            "prompts_per_round": self.prompts_per_round,
            "hall_of_fame_size": self.hall_of_fame_size if self.use_hall_of_fame else 0,
        }

    def get_optimizer_metadata(self) -> dict[str, Any]:
        metadata: dict[str, Any] = {
            "prompts_per_round": self.prompts_per_round,
            "enable_context": self.enable_context,
            "num_task_examples": self.num_task_examples,
            "task_context_columns": self.task_context_columns,
            "max_context_tokens": self.max_context_tokens,
            "n_threads": self.n_threads,
            "use_hall_of_fame": self.use_hall_of_fame,
            "hall_of_fame_size": self.hall_of_fame.max_size
            if self.hall_of_fame
            else constants.META_PROMPT_DEFAULT_HALL_OF_FAME_SIZE,
            "pattern_extraction_interval": self.hall_of_fame.pattern_extraction_interval
            if self.hall_of_fame
            else constants.META_PROMPT_DEFAULT_HALL_OF_FAME_PATTERN_EXTRACTION_INTERVAL,
            "pattern_injection_rate": self.pattern_injection_rate,
        }
        return metadata

    def get_config(self, context: OptimizationContext) -> dict[str, Any]:
        """Return optimizer-specific configuration for display."""
        return {
            "optimizer": self.__class__.__name__,
            "max_trials": context.max_trials,
            "prompts_per_round": self.prompts_per_round,
            "n_samples": context.n_samples,
            "auto_continue": context.extra_params.get("auto_continue", False),
            "optimize_tools": context.extra_params.get("optimize_tools"),
            "tool_names": context.extra_params.get("tool_names"),
            "validation_dataset": getattr(context.validation_dataset, "name", None)
            if context.validation_dataset is not None
            else None,
        }

    def run_optimization(
        self,
        context: OptimizationContext,
    ) -> AlgorithmResult:
        """
        Run the MetaPrompt optimization algorithm.

        Uses LLM-based meta-reasoning to iteratively improve prompts by:
        1. Generating candidate prompts using reasoning about improvements
        2. Evaluating candidates using self.evaluate()
        3. Tracking best prompts and updating hall of fame
        4. Repeating until max_trials or early stop

        Args:
            context: The optimization context with prompts, dataset, metric, etc.

        Returns:
            AlgorithmResult with best prompts, score, history, and metadata.
        """
        prompts = context.prompts
        dataset = context.dataset
        metric = context.metric
        max_trials = context.max_trials
        auto_continue = context.extra_params.get("auto_continue", False)
        optimize_tools = context.extra_params.get("optimize_tools")
        tool_names = context.extra_params.get("tool_names")
        is_single_prompt_optimization = context.is_single_prompt_optimization
        optimization_id = context.optimization_id

        self.auto_continue = auto_continue
        self.dataset = dataset
        self.set_default_dataset_split(
            "validation" if context.validation_dataset is not None else "train"
        )
        best_prompts = prompts
        is_bundle = not is_single_prompt_optimization

        # Use baseline score from context (computed by base class)
        initial_score = cast(float, context.baseline_score)
        best_score = initial_score
        # History is tracked by the shared state helper.
        self._history_builder.clear()

        # Calculate the maximum number of rounds
        self._total_rounds = max(1, max_trials // self.prompts_per_round + 1)
        round_num = 0
        self._set_reporter(object())
        try:
            while context.trials_completed < max_trials:
                # Check should_stop flag at start of each round
                if self._should_stop_context(context):
                    break

                self._current_round = round_num
                debug_log(
                    "round_loop_start",
                    round_index=round_num + 1,
                    trials_completed=context.trials_completed,
                    max_trials=max_trials,
                )

                # Check if we should extract patterns from hall of fame
                if not optimize_tools:
                    halloffame_ops.maybe_extract_hof_patterns(
                        optimizer=self,
                        current_trial=context.trials_completed,
                        metric_name=metric.__name__,
                    )

                prompts_this_round = min(
                    self.prompts_per_round, max_trials - context.trials_completed
                )

                if optimize_tools:
                    from ...utils.toolcalling.ops import (
                        toolcalling as toolcalling_utils,
                    )
                    from ...utils.display import display_tool_description

                    if not is_single_prompt_optimization:
                        raise ValueError(
                            "Tool description optimization only supports single prompts."
                        )
                    reporter = None
                    if self.verbose >= 1:
                        import json as _json
                        import rich.text as _rich_text

                        def _reporter(
                            description: str, name: str, metadata: dict[str, Any]
                        ) -> None:
                            text = _rich_text.Text()
                            text.append(description)
                            raw_tool = metadata.get("raw_tool") or {}
                            parameters = (
                                raw_tool.get("function", {}).get("parameters")
                                if isinstance(raw_tool, dict)
                                else None
                            )
                            if parameters:
                                text.append(
                                    "\n\nTool parameters (reference only):\n",
                                    style="dim",
                                )
                                text.append(
                                    _json.dumps(
                                        parameters,
                                        indent=2,
                                        sort_keys=True,
                                        default=str,
                                    ),
                                    style="dim",
                                )
                            display_tool_description(
                                text, title=f"tool: {name}", style="cyan"
                            )

                        reporter = _reporter
                    single_candidates = (
                        toolcalling_utils.generate_tool_description_candidates(
                            optimizer=self,
                            current_prompt=list(best_prompts.values())[0],
                            best_score=best_score,
                            round_num=round_num,
                            previous_rounds=self.get_history_rounds(),
                            metric=metric,
                            optimization_id=optimization_id,
                            project_name=self.project_name,
                            build_history_context_fn=self._build_history_context,
                            tool_names=tool_names,
                            tool_description_reporter=reporter,
                        )
                    )
                    prompt_key = next(iter(best_prompts.keys()))
                    candidate_prompts = [
                        {prompt_key: prompt}
                        for prompt in single_candidates[:prompts_this_round]
                    ]
                else:
                    candidate_prompts = candidate_ops.generate_round_candidates(
                        optimizer=self,
                        best_prompts=best_prompts,
                        best_score=best_score,
                        round_num=round_num,
                        previous_rounds=self.get_history_rounds(),
                        metric=metric,
                        prompts_this_round=prompts_this_round,
                        build_history_context_fn=self._build_history_context,
                        get_task_context_fn=self._get_task_context,
                        optimization_id=optimization_id,
                        project_name=self.project_name,
                        is_single_prompt_optimization=is_single_prompt_optimization,
                        winning_patterns=halloffame_ops.get_patterns_for_injection(
                            self
                        ),
                    )
                if not candidate_prompts:
                    logger.warning(
                        "No candidate prompts generated in round %s", round_num
                    )
                    break

                # Step 2. Score each candidate prompt
                prompt_scores, _ = evaluation_ops.score_candidate_prompts(
                    optimizer=self,
                    context=context,
                    candidate_prompts=candidate_prompts,
                    best_score=best_score,
                )

                # Step 3. Identify potential improvements
                if not prompt_scores:
                    logger.warning(
                        "No prompts were successfully evaluated in this round"
                    )
                    break

                (
                    prompt_scores,
                    best_candidate_this_round,
                    best_cand_score_avg,
                    improvement,
                ) = evaluation_ops.select_best_candidate(
                    prompt_scores=prompt_scores,
                    best_score=best_score,
                )

                if not optimize_tools:
                    add_best_candidate_to_hof(
                        optimizer=self,
                        best_candidate_this_round=best_candidate_this_round,
                        best_cand_score_avg=best_cand_score_avg,
                        initial_score=initial_score,
                        current_trial=context.trials_completed,
                        metric_name=metric.__name__,
                        is_bundle=is_bundle,
                    )

                history_ops.record_round_history(
                    optimizer=self,
                    context=context,
                    round_num=round_num,
                    prompt_scores=prompt_scores,
                    best_cand_score_avg=best_cand_score_avg,
                    best_candidate_this_round=best_candidate_this_round,
                    improvement=improvement,
                )

                if best_cand_score_avg > best_score:
                    best_score = best_cand_score_avg
                    best_prompts = best_candidate_this_round

                # Log round completion before increment to keep indices consistent.
                debug_log(
                    "round_loop_end",
                    round_index=round_num + 1,
                    best_score=best_score,
                    trials_completed=context.trials_completed,
                )
                # Increment round counter
                round_num += 1
        finally:
            # finish_reason, stopped_early, stop_reason are handled by base class
            self._clear_reporter()

        history = self.get_history_rounds()
        return result_ops.build_algorithm_result(
            best_prompts=best_prompts,
            best_score=best_score,
            history=history,
            prompts_per_round=self.prompts_per_round,
            hall_of_fame_size=self.hall_of_fame_size,
            use_hall_of_fame=self.use_hall_of_fame,
        )

    def _get_task_context(self, metric: MetricFunction) -> tuple[str, int]:
        """Get task-specific context from the dataset and metric (delegates to ops)."""
        return context_learning_ops.get_task_context(
            dataset=self.dataset,
            metric=metric,
            num_examples=self.num_task_examples,
            columns=self.task_context_columns,
            max_tokens=self.max_context_tokens,
            model=self.model,
            extract_metric_understanding=self.extract_metric_understanding,
            seed=self.seed,
        )

    def _build_history_context(
        self, previous_rounds: Sequence[OptimizationRound]
    ) -> str:
        """Build context from Hall of Fame and previous optimization rounds."""
        top_prompts_to_show = max(
            self.prompts_per_round, self.synthesis_prompts_per_round
        )
        return history_ops.build_history_context(
            list(previous_rounds),
            hall_of_fame=self.hall_of_fame if hasattr(self, "hall_of_fame") else None,
            pretty_mode=bool(self.verbose),
            top_prompts_per_round=top_prompts_to_show,
        )


__all__ = ["MetaPromptOptimizer"]
