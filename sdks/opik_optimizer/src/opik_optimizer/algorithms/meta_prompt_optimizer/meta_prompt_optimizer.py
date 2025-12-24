import logging
from typing import Any, cast

from opik import Dataset
from ...base_optimizer import BaseOptimizer, OptimizationRound
from ...api_objects import chat_prompt
from ...api_objects.types import MetricFunction
from ...optimization_result import OptimizationResult
from ...agents import OptimizableAgent, LiteLLMAgent
from ... import _throttle
from . import reporting
from .ops.halloffame_ops import PromptHallOfFame
from ..._llm_calls import StructuredOutputParsingError
from litellm.exceptions import BadRequestError

# Import ops modules
from .ops import candidate_ops, context_ops, result_ops

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
    """

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
    ) -> None:
        super().__init__(
            model=model,
            verbose=verbose,
            seed=seed,
            model_parameters=model_parameters,
            name=name,
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
            )
            logger.debug(
                f"Hall of Fame enabled: size={self.hall_of_fame_size}, "
                f"extraction_interval={self.pattern_extraction_interval}"
            )

        logger.debug(f"Initialized MetaPromptOptimizer with model={model}")
        logger.debug(f"Prompts/round: {prompts_per_round}")

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

    def optimize_prompt(
        self,
        prompt: chat_prompt.ChatPrompt | dict[str, chat_prompt.ChatPrompt],
        dataset: Dataset,
        metric: MetricFunction,
        agent: OptimizableAgent | None = None,
        experiment_config: dict | None = None,
        n_samples: int | None = None,
        auto_continue: bool = False,
        project_name: str = "Optimization",
        optimization_id: str | None = None,
        validation_dataset: Dataset | None = None,
        max_trials: int = 10,
        *args: Any,
        **kwargs: Any,
    ) -> OptimizationResult:
        """
        Optimize a prompt using LLM-based meta-reasoning to iteratively improve performance.

        The optimizer evaluates the initial prompt, uses an LLM to reason about improvements,
        generates candidate variations, and iteratively selects the best performers until
        max_trials is reached.

        Args:
            prompt: The ChatPrompt to optimize. Can include system/user/assistant messages,
                tools, and model configuration.
            dataset: Opik Dataset containing evaluation examples. Each item is passed to the
                prompt during evaluation.
            metric: Evaluation function that takes (dataset_item, llm_output) and returns a
                score (float). Higher scores indicate better performance.
            validation_dataset: Optional validation dataset for evaluating candidates. When provided,
                the optimizer uses the training dataset for understanding failure modes and generating
                improvements, then evaluates candidates on the validation dataset to prevent overfitting.
            experiment_config: Optional metadata dictionary to log with Opik experiments.
                Useful for tracking experiment parameters and context.
            n_samples: Number of dataset items to use per evaluation. If None, uses full dataset.
                Lower values speed up optimization but may be less reliable.
            auto_continue: If True, optimizer may continue beyond max_trials if improvements
                are still being found.
            agent_class: Custom agent class for prompt execution. If None, uses default
                LiteLLM-based agent. Must inherit from OptimizableAgent.
            project_name: Opik project name for logging traces and experiments. Default: "Optimization"
            optimization_id: Optional ID to use when creating the Opik optimization run; when
                provided it must be a valid UUIDv7 string.
            max_trials: Maximum total number of prompts to evaluate across all rounds.
                Optimizer stops when this limit is reached.

        Returns:
            OptimizationResult: Contains the best prompt found, final score, optimization
                history, and metadata about the optimization run.

        Example:
            ```python
            from opik_optimizer import MetaPromptOptimizer, ChatPrompt
            from opik import Opik

            client = Opik()
            dataset = client.get_dataset("my_dataset")

            prompt = ChatPrompt(
                system="You are a helpful assistant.",
                user_template="Answer this question: {question}"
            )

            def accuracy_metric(dataset_item, llm_output):
                return 1.0 if llm_output == dataset_item["expected"] else 0.0

            optimizer = MetaPromptOptimizer(model="gpt-4o")
            result = optimizer.optimize_prompt(
                prompt=prompt,
                dataset=dataset,
                metric=accuracy_metric,
                max_trials=10
            )

            print(f"Best score: {result.best_score}")
            print(f"Best prompt: {result.best_prompt}")
            ```
        """
        self._validate_optimization_inputs(
            prompt, dataset, metric, support_content_parts=True
        )

        if agent is None:
            agent = LiteLLMAgent(project_name=project_name)

        optimizable_prompts: dict[str, chat_prompt.ChatPrompt]
        if isinstance(prompt, chat_prompt.ChatPrompt):
            optimizable_prompts = {prompt.name: prompt}
            is_single_prompt_optimization = True
        else:
            optimizable_prompts = prompt
            is_single_prompt_optimization = False

        # Set project name from parameter
        self.project_name = project_name

        dataset_name = getattr(dataset, "name", dataset.__class__.__name__)
        dataset_id = getattr(dataset, "id", None)

        # Update experiment_config with validation_dataset if provided
        if validation_dataset is not None:
            experiment_config = experiment_config or {}
            experiment_config["validation_dataset"] = getattr(
                validation_dataset, "name", validation_dataset.__class__.__name__
            )
            experiment_config["validation_dataset_id"] = getattr(
                validation_dataset, "id", None
            )

        total_items = len(dataset.get_items())
        if n_samples is not None and n_samples > total_items:
            logger.warning(
                f"Requested n_samples ({n_samples}) is larger than dataset size ({total_items}). Using full dataset."
            )
            n_samples = None

        optimization = None
        try:
            optimization = self.opik_client.create_optimization(
                dataset_name=dataset_name,
                objective_name=metric.__name__,
                metadata=self._build_optimization_metadata(),
                name=self.name,
                optimization_id=optimization_id,
            )
            self.current_optimization_id = optimization.id
            logger.debug(f"Created optimization with ID: {optimization.id}")
        except Exception as e:
            logger.warning(
                f"Opik server does not support optimizations: {e}. Please upgrade opik."
            )
            optimization = None
            self.current_optimization_id = None

        reporting.display_header(
            algorithm=self.__class__.__name__,
            optimization_id=optimization.id if optimization is not None else None,
            dataset_id=dataset_id,
            verbose=self.verbose,
        )
        reporting.display_configuration(
            messages=optimizable_prompts,
            optimizer_config={
                "optimizer": self.__class__.__name__,
                "max_trials": max_trials,
                "prompts_per_round": self.prompts_per_round,
                "n_samples": n_samples,
                "auto_continue": auto_continue,
                "validation_dataset": getattr(validation_dataset, "name", None)
                if validation_dataset is not None
                else None,
            },
            verbose=self.verbose,
        )

        try:
            optimization_id = optimization.id if optimization is not None else None
            result = self._optimize_prompt(
                optimization_id=optimization_id,
                prompts=optimizable_prompts,
                dataset=dataset,
                metric=metric,
                agent=agent,
                validation_dataset=validation_dataset,
                experiment_config=experiment_config,
                max_trials=max_trials,
                n_samples=n_samples,
                auto_continue=auto_continue,
                is_single_prompt_optimization=is_single_prompt_optimization,
            )
            if optimization:
                self._update_optimization(optimization, status="completed")
                logger.debug("Optimization completed successfully")
            return result
        except Exception as e:
            logger.error(f"Optimization failed: {e}")
            if optimization:
                self._update_optimization(optimization, status="cancelled")
                logger.debug("Optimization marked as cancelled")
            raise e

    def _optimize_prompt(
        self,
        optimization_id: str | None,
        prompts: dict[str, chat_prompt.ChatPrompt],
        dataset: Dataset,
        agent: OptimizableAgent,
        validation_dataset: Dataset | None,
        metric: MetricFunction,
        experiment_config: dict | None,
        max_trials: int,
        n_samples: int | None,
        auto_continue: bool,
        is_single_prompt_optimization: bool = False,
        _tool_panel_style: str = "bright_magenta",
    ) -> OptimizationResult:
        self.auto_continue = auto_continue
        self.dataset = dataset
        self.prompts = prompts
        self._reset_counters()  # Reset counters for run
        initial_prompts = prompts
        is_bundle = True
        dataset_id = getattr(dataset, "id", None)

        # Logic on which dataset to use for scoring
        evaluation_dataset = (
            validation_dataset if validation_dataset is not None else dataset
        )

        with reporting.display_evaluation(verbose=self.verbose) as baseline_reporter:
            if validation_dataset is not None:
                experiment_config = experiment_config or {}
                experiment_config["validation_dataset"] = getattr(
                    validation_dataset, "name", validation_dataset.__class__.__name__
                )
                experiment_config["validation_dataset_id"] = getattr(
                    validation_dataset, "id", None
                )

            initial_score = self.evaluate_prompt(
                prompt=prompts,
                agent=agent,
                dataset=dataset,
                metric=metric,
                n_samples=n_samples,
                n_threads=self.n_threads,
                verbose=self.verbose,
                experiment_config=self._prepare_experiment_config(
                    prompt=prompts,
                    dataset=dataset,
                    metric=metric,
                    agent=agent,
                    experiment_config=experiment_config,
                    is_single_prompt_optimization=is_single_prompt_optimization,
                ),
            )

            best_score = initial_score
            best_prompts = initial_prompts
            rounds: list[OptimizationRound] = []

            baseline_reporter.set_score(initial_score)

        reporting.display_optimization_start_message(verbose=self.verbose)

        # Calculate the maximum number of rounds, we will stop early if we hit the
        # max_trials limit
        estimated_rounds = max(1, max_trials // self.prompts_per_round + 1)

        with reporting.display_round_progress(
            estimated_rounds, verbose=self.verbose
        ) as round_reporter:
            round_num = 0
            trials_used = 0

            while trials_used < max_trials:
                round_reporter.round_start(round_num)
                previous_best_score = best_score

                # Check if we should extract patterns from hall of fame
                if self.hall_of_fame and self.hall_of_fame.should_extract_patterns(
                    trials_used
                ):
                    logger.info(
                        f"Extracting patterns from hall of fame at trial {trials_used}"
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
                    self.prompts_per_round, max_trials - trials_used
                )

                try:
                    bundle_candidates = candidate_ops.generate_agent_bundle_candidates(
                        optimizer=self,
                        current_prompts=best_prompts,
                        best_score=best_score,
                        round_num=round_num,
                        previous_rounds=rounds,
                        metric=metric,
                        optimization_id=optimization_id,
                        project_name=self.project_name,
                        build_history_context_fn=self._build_history_context,
                        get_task_context_fn=self._get_task_context,
                    )
                    # Extract prompts from bundle candidates and limit to prompts_this_round
                    candidate_prompts = [
                        bundle.prompts
                        for bundle in bundle_candidates[:prompts_this_round]
                    ]

                except Exception as e:
                    if isinstance(e, (BadRequestError, StructuredOutputParsingError)):
                        raise
                    round_reporter.failed_to_generate(prompts_this_round, str(e))
                    # Regular generation failed - break to prevent infinite loop
                    trials_used += prompts_this_round
                    break

                # Step 2. Score each candidate prompt
                prompt_scores: list[tuple[Any, float]] = []
                current_round_best_score = (
                    best_score  # Track best score within this round
                )
                for candidate_count, prompts in enumerate(candidate_prompts):
                    with reporting.display_prompt_candidate_scoring_report(
                        verbose=self.verbose
                    ) as eval_report:
                        eval_report.set_generated_prompts(candidate_count, prompts)

                        prompt_score = self.evaluate_prompt(
                            prompt=prompts,
                            agent=agent,
                            dataset=evaluation_dataset,
                            metric=metric,
                            n_samples=n_samples,
                            n_threads=self.n_threads,
                            verbose=self.verbose,
                        )

                        # Compare against the best score seen so far in this round
                        eval_report.set_final_score(
                            current_round_best_score, prompt_score
                        )
                        trials_used += 1

                        # Update the round's best score if this candidate is better
                        if prompt_score > current_round_best_score:
                            current_round_best_score = prompt_score

                    prompt_scores.append((prompts, prompt_score))

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
                round_reporter.round_end(round_num, best_cand_score_avg, best_score)

                # Cast to ChatPrompt for use in round_data and hall of fame
                best_candidate_chat = cast(
                    chat_prompt.ChatPrompt, best_candidate_this_round
                )

                # Add best candidate to hall of fame if qualified
                if self.hall_of_fame and best_cand_score_avg > 0 and not is_bundle:
                    from .ops.halloffame_ops import HallOfFameEntry

                    entry = HallOfFameEntry(
                        prompt_messages=best_candidate_chat.get_messages(),
                        score=best_cand_score_avg,
                        trial_number=trials_used,
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
                            f"trial={trials_used}"
                        )

                round_data = self._create_round_data(
                    round_num=round_num,
                    current_best_prompt=best_candidate_chat,
                    current_best_score=best_cand_score_avg,
                    best_prompt_overall=best_prompts,
                    evaluated_candidates=prompt_scores,
                    previous_best_score=previous_best_score,
                    improvement_this_round=improvement,
                )
                rounds.append(round_data)
                self._add_to_history(round_data)

                if best_cand_score_avg > best_score:
                    best_score = best_cand_score_avg
                    best_prompts = best_candidate_this_round

                # Increment counters
                round_num += 1

        # Prepare result prompts based on single vs multi-prompt mode
        result_prompt: chat_prompt.ChatPrompt | dict[str, chat_prompt.ChatPrompt]
        result_initial_prompt: (
            chat_prompt.ChatPrompt | dict[str, chat_prompt.ChatPrompt]
        )
        if is_single_prompt_optimization:
            result_prompt = list(best_prompts.values())[0]
            result_initial_prompt = list(initial_prompts.values())[0]
        else:
            result_prompt = best_prompts
            result_initial_prompt = initial_prompts

        reporting.display_result(
            initial_score=initial_score,
            best_score=best_score,
            prompt=result_prompt,
            verbose=self.verbose,
        )

        return result_ops.create_result(
            optimizer_class_name=self.__class__.__name__,
            metric=metric,
            prompt=result_prompt,
            initial_prompt=result_initial_prompt,
            best_score=best_score,
            initial_score=initial_score,
            rounds=rounds,
            dataset_id=dataset_id,
            optimization_id=optimization_id,
            llm_call_counter=self.llm_call_counter,
            tool_call_counter=self.tool_call_counter,
        )

    def _create_round_data(
        self,
        round_num: int,
        current_best_prompt: Any,
        current_best_score: float,
        best_prompt_overall: Any,
        evaluated_candidates: list[tuple[Any, float]],
        previous_best_score: float,
        improvement_this_round: float,
    ) -> OptimizationRound:
        """Create an OptimizationRound object with the current round's data (delegates to ops)."""
        # For bundles, use a representative ChatPrompt to keep validation happy.
        current_prompt_repr = (
            next(iter(current_best_prompt.values()))
            if isinstance(current_best_prompt, dict) and current_best_prompt
            else current_best_prompt
        )
        best_prompt_repr = (
            next(iter(best_prompt_overall.values()))
            if isinstance(best_prompt_overall, dict) and best_prompt_overall
            else best_prompt_overall
        )
        return result_ops.create_round_data(
            round_num=round_num,
            current_best_prompt=current_prompt_repr,
            current_best_score=current_best_score,
            best_prompt_overall=best_prompt_repr,
            evaluated_candidates=evaluated_candidates,
            previous_best_score=previous_best_score,
            improvement_this_round=improvement_this_round,
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

    def _generate_synthesis_prompts(
        self,
        current_prompt: chat_prompt.ChatPrompt,
        best_score: float,
        round_num: int,
        previous_rounds: list[OptimizationRound],
        metric: MetricFunction,
        optimization_id: str | None = None,
        project_name: str | None = None,
    ) -> list[chat_prompt.ChatPrompt]:
        """Generate synthesis prompts that combine top performers."""
        return candidate_ops.generate_synthesis_prompts(
            optimizer=self,
            current_prompt=current_prompt,
            best_score=best_score,
            previous_rounds=previous_rounds,
            metric=metric,
            get_task_context_fn=self._get_task_context,
            optimization_id=optimization_id,
            project_name=project_name,
        )

    def _build_history_context(self, previous_rounds: list[OptimizationRound]) -> str:
        """Build context from Hall of Fame and previous optimization rounds."""
        top_prompts_to_show = max(
            self.prompts_per_round, self.synthesis_prompts_per_round
        )
        return context_ops.build_history_context(
            previous_rounds,
            hall_of_fame=self.hall_of_fame if hasattr(self, "hall_of_fame") else None,
            pretty_mode=self.prettymode_prompt_history,
            top_prompts_per_round=top_prompts_to_show,
        )


__all__ = ["MetaPromptOptimizer"]
