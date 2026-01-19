import logging
from typing import Any, cast

from opik import Dataset
from ...base_optimizer import (
    AlgorithmResult,
    BaseOptimizer,
    OptimizationContext,
)
from ...utils.prompt_library import PromptOverrides
from ...api_objects import chat_prompt
from ...api_objects.types import MetricFunction
from ...utils import throttle as _throttle
from ...utils.logging import debug_log
from collections.abc import Sequence
from .ops.halloffame_ops import PromptHallOfFame
from ...core.results import OptimizationRound

# Import ops modules
from .ops import candidate_ops, context_ops, result_ops
from . import prompts as meta_prompts

# Set up logging
logger = logging.getLogger(__name__)  # Gets logger configured by setup_logging

_rate_limiter = _throttle.get_rate_limiter_for_current_opik_installation()


class MetaPromptOptimizer(BaseOptimizer):
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

    Args:
        model: LiteLLM model name for optimizer's internal reasoning/generation calls
        model_parameters: Optional dict of LiteLLM parameters for optimizer's internal LLM calls.
            Common params: temperature, max_tokens, max_completion_tokens, top_p.
            See: https://docs.litellm.ai/docs/completion/input
        prompts_per_round: Number of candidate prompts to generate per optimization round
        enable_context: Whether to include task-specific context when reasoning about improvements
        num_task_examples: Number of dataset examples to show in task context (default: 10)
        task_context_columns: Specific dataset columns to include in context (None = all input columns)
        n_threads: Number of parallel threads for prompt evaluation
        verbose: Controls internal logging/progress bars (0=off, 1=on)
        seed: Random seed for reproducibility
        use_hall_of_fame: Enable Hall of Fame pattern extraction and re-injection
        prettymode_prompt_history: Display prompt history in pretty format (True) or JSON (False)
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
    }

    # --- Constants for Default Configuration ---
    DEFAULT_PROMPTS_PER_ROUND = 4  # Same as DEFAULT_NUM_GENERATIONS
    DEFAULT_SYNTHESIS_PROMPTS_PER_ROUND = 2
    DEFAULT_SYNTHESIS_START_ROUND = 3
    DEFAULT_SYNTHESIS_ROUND_INTERVAL = 3
    DEFAULT_NUM_THREADS = 12
    DEFAULT_SEED = 42
    DEFAULT_NUM_TASK_EXAMPLES = 5
    DEFAULT_HALL_OF_FAME_SIZE = 10
    DEFAULT_PATTERN_EXTRACTION_INTERVAL = 5
    DEFAULT_PATTERN_INJECTION_RATE = 0.6
    DEFAULT_DATASET_CONTEXT_MAX_TOKENS = 10000
    DEFAULT_DATASET_CONTEXT_RATIO = 0.25
    DEFAULT_PRETTYMODE_PROMPT_HISTORY = True
    DEFAULT_EXTRACT_METRIC_UNDERSTANDING = True
    # TODO: Refactor and make global - this should be a configuration parameter
    # Other optimizers only optimize system prompts, not user prompts
    # Setting this to True allows more flexibility
    DEFAULT_ALLOW_USER_PROMPT_OPTIMIZATION = False

    def __init__(
        self,
        model: str = "gpt-4o",
        model_parameters: dict[str, Any] | None = None,
        prompts_per_round: int = DEFAULT_PROMPTS_PER_ROUND,
        enable_context: bool = True,
        num_task_examples: int = DEFAULT_NUM_TASK_EXAMPLES,
        task_context_columns: list[str] | None = None,
        n_threads: int = DEFAULT_NUM_THREADS,
        verbose: int = 1,
        seed: int = DEFAULT_SEED,
        name: str | None = None,
        use_hall_of_fame: bool = True,
        prettymode_prompt_history: bool = DEFAULT_PRETTYMODE_PROMPT_HISTORY,
        prompt_overrides: PromptOverrides = None,
        skip_perfect_score: bool = True,
        perfect_score: float = 0.95,
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
        self.synthesis_prompts_per_round = self.DEFAULT_SYNTHESIS_PROMPTS_PER_ROUND
        self.synthesis_start_round = self.DEFAULT_SYNTHESIS_START_ROUND
        self.synthesis_round_interval = self.DEFAULT_SYNTHESIS_ROUND_INTERVAL
        self.n_threads = n_threads
        self.dataset: Dataset | None = None
        self.enable_context = enable_context
        self.num_task_examples = num_task_examples
        self.task_context_columns = task_context_columns
        self.selection_strategy = "best_by_metric"

        # Calculate token budget for task context data stuffing (dataset examples only)
        # This is ONLY used for adaptive fitting logic in get_task_context()
        self.max_context_tokens = self._calculate_max_context_tokens()

        # Hall of Fame for pattern mining
        self.use_hall_of_fame = use_hall_of_fame
        self.prettymode_prompt_history = prettymode_prompt_history
        self.extract_metric_understanding = self.DEFAULT_EXTRACT_METRIC_UNDERSTANDING
        self.allow_user_prompt_optimization = (
            self.DEFAULT_ALLOW_USER_PROMPT_OPTIMIZATION
        )
        self.hall_of_fame_size = self.DEFAULT_HALL_OF_FAME_SIZE
        self.pattern_extraction_interval = self.DEFAULT_PATTERN_EXTRACTION_INTERVAL
        self.pattern_injection_rate = self.DEFAULT_PATTERN_INJECTION_RATE

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

    def _calculate_max_context_tokens(self) -> int:
        """
        Calculate token budget for task context data stuffing (dataset examples ONLY).

        This limit is ONLY used in get_task_context() for adaptive fitting of dataset examples.
        It determines how many examples and how much truncation to use when building task context.

        Uses ~25% of model's max tokens, capped at DEFAULT_MAX_DATASET_CONTEXT_MAX_TOKENS.
        Falls back to absolute max for custom models where litellm can't determine limits.
        """
        try:
            from litellm import get_max_tokens

            model_max_tokens: int = get_max_tokens(self.model)  # type: ignore[assignment]
            # Use ~25% of model's context for dataset examples
            calculated_max = int(model_max_tokens * self.DEFAULT_DATASET_CONTEXT_RATIO)
            logger.debug(
                f"Model {self.model} max tokens: {model_max_tokens}, "
                f"calculated dataset context budget: {calculated_max}"
            )
        except Exception as e:
            logger.debug(
                f"Could not get max tokens for model {self.model}: {e}. "
                f"Using default max: {self.DEFAULT_DATASET_CONTEXT_MAX_TOKENS}"
            )
            calculated_max = self.DEFAULT_DATASET_CONTEXT_MAX_TOKENS

        # Apply absolute safety limit (for custom models or huge context windows)
        max_tokens = min(calculated_max, self.DEFAULT_DATASET_CONTEXT_MAX_TOKENS)
        logger.debug(f"Final dataset context token budget: {max_tokens}")
        return max_tokens

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
            else self.DEFAULT_HALL_OF_FAME_SIZE,
            "pattern_extraction_interval": self.hall_of_fame.pattern_extraction_interval
            if self.hall_of_fame
            else self.DEFAULT_PATTERN_EXTRACTION_INTERVAL,
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
                    "round_start",
                    round_index=round_num + 1,
                    trials_completed=context.trials_completed,
                    max_trials=max_trials,
                )

                # Check if we should extract patterns from hall of fame
                if self.hall_of_fame and self.hall_of_fame.should_extract_patterns(
                    context.trials_completed
                ):
                    logger.info(
                        f"Extracting patterns from hall of fame at trial {context.trials_completed}"
                    )
                    new_patterns = self.hall_of_fame.extract_patterns(
                        model=self.model,
                        model_parameters=self.model_parameters,
                        metric_name=metric.__name__,
                    )
                    if new_patterns:
                        logger.info(f"Extracted {len(new_patterns)} new patterns")
                        for i, pattern in enumerate(new_patterns[:3], 1):
                            logger.debug(f"  Pattern {i}: {pattern[:100]}...")

                prompts_this_round = min(
                    self.prompts_per_round, max_trials - context.trials_completed
                )

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
                    winning_patterns=(
                        self.hall_of_fame.get_patterns_for_injection()
                        if self.hall_of_fame
                        else None
                    ),
                )
                if not candidate_prompts:
                    logger.warning(
                        "No candidate prompts generated in round %s", round_num
                    )
                    break

                # Step 2. Score each candidate prompt
                prompt_scores: list[tuple[Any, float]] = []
                current_round_best_score = best_score
                self._total_candidates_in_round = len(candidate_prompts)

                for candidate_count, candidate in enumerate(candidate_prompts):
                    # Check should_stop before each evaluation
                    if self._should_stop_context(context):
                        break

                    # Update progress tracking for display
                    self._current_candidate = candidate_count

                    # self.evaluate() handles:
                    # - Progress tracking (trials_completed++)
                    # - Early stop checking (sets should_stop flag)
                    # - Best score/prompt tracking
                    # - Display via _on_evaluation -> get_progress_state
                    prompt_score = self.evaluate(candidate)

                    # Update the round's best score if this candidate is better
                    if prompt_score > current_round_best_score:
                        current_round_best_score = prompt_score

                    prompt_scores.append((candidate, prompt_score))

                # Step 3. Identify potential improvements
                if not prompt_scores:
                    logger.warning(
                        "No prompts were successfully evaluated in this round"
                    )
                    break

                prompt_scores.sort(key=lambda x: x[1], reverse=True)
                best_candidate_this_round, best_cand_score_avg = prompt_scores[0]
                improvement = result_ops.calculate_improvement(
                    best_cand_score_avg, best_score
                )

                # Add best candidate to hall of fame if qualified (single prompt only)
                if self.hall_of_fame and best_cand_score_avg > 0 and not is_bundle:
                    from .types import HallOfFameEntry

                    # For single prompt optimization, extract the ChatPrompt from dict
                    if isinstance(best_candidate_this_round, dict):
                        best_candidate_chat = list(best_candidate_this_round.values())[
                            0
                        ]
                    else:
                        best_candidate_chat = cast(
                            chat_prompt.ChatPrompt, best_candidate_this_round
                        )

                    entry = HallOfFameEntry(
                        prompt_messages=best_candidate_chat.get_messages(),
                        score=best_cand_score_avg,
                        trial_number=context.trials_completed,
                        improvement_over_baseline=(
                            (best_cand_score_avg - initial_score) / initial_score
                            if initial_score > 0
                            else 0
                        ),
                        metric_name=metric.__name__,
                    )
                    if self.hall_of_fame.add(entry):
                        logger.debug(
                            f"Added to hall of fame: score={best_cand_score_avg:.3f}, "
                            f"trial={context.trials_completed}"
                        )

                round_handle = self.begin_round()
                self.set_selection_meta(
                    {
                        "selection_policy": self.selection_strategy,
                        "score_used": best_cand_score_avg,
                        "candidate_count": len(prompt_scores),
                    }
                )
                # Record each evaluated candidate as trials
                for idx, (cand_prompt, cand_score) in enumerate(prompt_scores):
                    self.record_candidate_entry(
                        prompt_or_payload=cand_prompt,
                        score=cand_score,
                        id=f"round{round_num}_cand",
                        metrics={"selection_score": cand_score},
                    )
                    self.post_candidate(
                        cand_prompt,
                        score=cand_score,
                        round_handle=round_handle,
                        extras={"round_num": round_num},
                    )
                # Flush round metadata/candidates
                self.post_round(
                    round_handle=round_handle,
                    best_score=best_cand_score_avg,
                    best_candidate=best_candidate_this_round,
                    stop_reason=context.finish_reason if context.should_stop else None,
                    extras={
                        "improvement": improvement,
                        "best_so_far": self._context.current_best_score
                        if self._context is not None
                        else None,
                    },
                )

                if best_cand_score_avg > best_score:
                    best_score = best_cand_score_avg
                    best_prompts = best_candidate_this_round

                # Increment round counter
                round_num += 1
                debug_log(
                    "round_end",
                    round_index=round_num,
                    best_score=best_score,
                    trials_completed=context.trials_completed,
                )
        finally:
            # finish_reason, stopped_early, stop_reason are handled by base class
            self._clear_reporter()

        history = self.get_history_rounds()

        return AlgorithmResult(
            best_prompts=best_prompts
            if isinstance(best_prompts, dict)
            else {"prompt": best_prompts},
            best_score=best_score,
            history=history,
            metadata={
                "prompts_per_round": self.prompts_per_round,
                "hall_of_fame_size": self.hall_of_fame_size
                if self.use_hall_of_fame
                else 0,
            },
        )

    def _get_task_context(self, metric: MetricFunction) -> tuple[str, int]:
        """Get task-specific context from the dataset and metric (delegates to ops)."""
        return context_ops.get_task_context(
            dataset=self.dataset,
            metric=metric,
            num_examples=self.num_task_examples,
            columns=self.task_context_columns,
            max_tokens=self.max_context_tokens,
            model=self.model,
            extract_metric_understanding=self.extract_metric_understanding,
        )

    def _build_history_context(
        self, previous_rounds: Sequence[OptimizationRound]
    ) -> str:
        """Build context from Hall of Fame and previous optimization rounds."""
        top_prompts_to_show = max(
            self.prompts_per_round, self.synthesis_prompts_per_round
        )
        return context_ops.build_history_context(
            list(previous_rounds),
            hall_of_fame=self.hall_of_fame if hasattr(self, "hall_of_fame") else None,
            pretty_mode=self.prettymode_prompt_history,
            top_prompts_per_round=top_prompts_to_show,
        )


__all__ = ["MetaPromptOptimizer"]
