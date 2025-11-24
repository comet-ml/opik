import logging
from typing import Any, cast
from concurrent.futures import ThreadPoolExecutor, as_completed
from collections.abc import Callable

import opik
from opik import Dataset
from ...base_optimizer import BaseOptimizer, OptimizationRound
from ...api_objects import chat_prompt
from ...optimization_result import OptimizationResult
from ...optimizable_agent import OptimizableAgent
from ... import _throttle
from . import reporting
from .ops.halloffame_ops import PromptHallOfFame
from .ops.candidate_ops import _sync_tool_description_in_system
from ..._llm_calls import StructuredOutputParsingError
from litellm.exceptions import BadRequestError
from ...mcp_utils.mcp_workflow import (
    MCPExecutionConfig,
    MCPSecondPassCoordinator,
    extract_tool_arguments,
)
from ...utils.prompt_segments import extract_prompt_segments

# Import ops modules
from .ops import evaluation_ops, candidate_ops, context_ops, result_ops

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

            model_max_tokens = get_max_tokens(self.model)
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

    def _evaluate_prompt(
        self,
        prompt: chat_prompt.ChatPrompt,
        dataset: opik.Dataset,
        metric: Callable,
        n_samples: int | None = None,
        dataset_item_ids: list[str] | None = None,
        experiment_config: dict | None = None,
        use_full_dataset: bool = True,
        optimization_id: str | None = None,
        mcp_config: MCPExecutionConfig | None = None,
        **kwargs: Any,
    ) -> float:
        """Evaluate a prompt on a dataset using a metric (delegates to ops)."""
        return evaluation_ops.evaluate_prompt(
            optimizer=self,
            prompt=prompt,
            dataset=dataset,
            metric=metric,
            n_samples=n_samples,
            dataset_item_ids=dataset_item_ids,
            experiment_config=experiment_config,
            use_full_dataset=use_full_dataset,
            optimization_id=optimization_id,
            mcp_config=mcp_config,
            **kwargs,
        )

    def optimize_prompt(
        self,
        prompt: chat_prompt.ChatPrompt | dict[str, chat_prompt.ChatPrompt],
        dataset: Dataset,
        metric: Callable,
        experiment_config: dict | None = None,
        n_samples: int | None = None,
        auto_continue: bool = False,
        agent_class: type[OptimizableAgent] | None = None,
        project_name: str = "Optimization",
        optimization_id: str | None = None,
        validation_dataset: Dataset | None = None,
        max_trials: int = 10,
        mcp_config: MCPExecutionConfig | None = None,
        candidate_generator: Callable[..., list[chat_prompt.ChatPrompt]] | None = None,
        candidate_generator_kwargs: dict[str, Any] | None = None,
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
            mcp_config: Optional MCP (Model Context Protocol) execution configuration for
                prompts that use external tools. Enables tool-calling workflows. Default: None
            candidate_generator: Optional custom function to generate candidate prompts.
                Overrides default meta-reasoning generator. Should return list[ChatPrompt].
            candidate_generator_kwargs: Optional kwargs to pass to candidate_generator.

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
        # Detect bundle mode
        is_bundle = isinstance(prompt, dict)

        # Use base class validation and setup methods
        if is_bundle:
            if mcp_config is not None:
                raise ValueError(
                    "Bundle optimization does not support MCP tool optimization."
                )
            bundle_prompt_dict = cast(dict[str, chat_prompt.ChatPrompt], prompt)
            first_agent_prompt = next(iter(bundle_prompt_dict.values()))
            self.agent_class = self._setup_agent_class(first_agent_prompt, agent_class)
        else:
            self._validate_optimization_inputs(prompt, dataset, metric)  # type: ignore[arg-type]
            self.agent_class = self._setup_agent_class(prompt, agent_class)  # type: ignore[arg-type]

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
        # store candidate kwargs for bundle eval
        self._current_candidate_generator_kwargs = candidate_generator_kwargs or {}
        try:
            optimization = self.opik_client.create_optimization(
                dataset_name=dataset_name,
                objective_name=getattr(metric, "__name__", str(metric)),
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
        display_messages = (
            [] if is_bundle else prompt.get_messages()  # type: ignore[union-attr]
        )
        display_tools = None if is_bundle else getattr(prompt, "tools", None)  # type: ignore[union-attr]
        reporting.display_configuration(
            messages=display_messages,
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
            tools=display_tools,
        )

        try:
            optimization_id = optimization.id if optimization is not None else None
            result = self._optimize_prompt(
                optimization_id=optimization_id,
                prompt=prompt,
                dataset=dataset,
                metric=metric,
                validation_dataset=validation_dataset,
                experiment_config=experiment_config,
                max_trials=max_trials,
                n_samples=n_samples,
                auto_continue=auto_continue,
                mcp_config=mcp_config,
                candidate_generator=candidate_generator,
                candidate_generator_kwargs=candidate_generator_kwargs,
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

    # FIXME: To be removed once MCP is fully supported
    def optimize_mcp(
        self,
        prompt: chat_prompt.ChatPrompt,
        dataset: Dataset,
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
        panel_style = kwargs.pop("tool_panel_style", "bright_magenta")

        if prompt.tools is None or not prompt.tools:
            raise ValueError("Prompt must include tools for MCP optimization")

        fallback_args_fn = fallback_arguments or extract_tool_arguments

        if fallback_invoker is None:
            function_map = prompt.function_map or {}
            fallback_invoker = function_map.get(tool_name)

        mcp_config = MCPExecutionConfig(
            coordinator=second_pass,
            tool_name=tool_name,
            fallback_arguments=fallback_args_fn,
            fallback_invoker=fallback_invoker,
            allow_tool_use_on_second_pass=allow_tool_use_on_second_pass,
        )

        tool_segment_id = f"tool:{tool_name}"
        segments = extract_prompt_segments(prompt)
        if tool_segment_id not in {segment.segment_id for segment in segments}:
            raise ValueError(f"Tool '{tool_name}' not present in prompt tools")

        return self._optimize_prompt(
            optimization_id=None,
            prompt=prompt,
            dataset=dataset,
            validation_dataset=None,
            metric=metric,
            experiment_config=experiment_config,
            max_trials=10,
            n_samples=n_samples,
            auto_continue=auto_continue,
            mcp_config=mcp_config,
            candidate_generator=self._generate_mcp_candidate_prompts,
            candidate_generator_kwargs={
                "tool_segment_id": tool_segment_id,
                "tool_name": tool_name,
                "panel_style": panel_style,
            },
            tool_panel_style=panel_style,
        )

    def _optimize_prompt(
        self,
        optimization_id: str | None,
        prompt: chat_prompt.ChatPrompt | dict[str, chat_prompt.ChatPrompt],
        dataset: Dataset,
        validation_dataset: Dataset | None,
        metric: Callable,
        experiment_config: dict | None,
        max_trials: int,
        n_samples: int | None,
        auto_continue: bool,
        mcp_config: MCPExecutionConfig | None = None,
        candidate_generator: Callable[..., Any] | None = None,
        candidate_generator_kwargs: dict[str, Any] | None = None,
        tool_panel_style: str = "bright_magenta",
    ) -> OptimizationResult:
        self.auto_continue = auto_continue
        self.dataset = dataset
        self.prompt = prompt
        self._reset_counters()  # Reset counters for run
        initial_prompt = prompt
        is_bundle = isinstance(prompt, dict)
        dataset_id = getattr(dataset, "id", None)

        # Logic on which dataset to use for scoring
        evaluation_dataset = (
            validation_dataset if validation_dataset is not None else dataset
        )

        current_prompt: Any = prompt
        with reporting.display_evaluation(verbose=self.verbose) as baseline_reporter:
            if validation_dataset is not None:
                experiment_config = experiment_config or {}
                experiment_config["validation_dataset"] = getattr(
                    validation_dataset, "name", validation_dataset.__class__.__name__
                )
                experiment_config["validation_dataset_id"] = getattr(
                    validation_dataset, "id", None
                )

            if is_bundle:
                initial_score = self._evaluate_bundle(
                    bundle_prompts=prompt,  # type: ignore[arg-type]
                    dataset=evaluation_dataset,
                    metric=metric,
                    n_samples=n_samples,
                    run_bundle_fn=candidate_generator_kwargs.get("run_bundle_fn")
                    if candidate_generator_kwargs
                    else None,  # type: ignore[arg-type]
                    bundle_plan=candidate_generator_kwargs.get("bundle_plan")
                    if candidate_generator_kwargs
                    else None,  # type: ignore[arg-type]
                    bundle_agent_class=candidate_generator_kwargs.get(
                        "bundle_agent_class"
                    )
                    if candidate_generator_kwargs
                    else None,  # type: ignore[arg-type]
                    bundle_agent_kwargs=candidate_generator_kwargs.get(
                        "bundle_agent_kwargs"
                    )
                    if candidate_generator_kwargs
                    else None,  # type: ignore[arg-type]
                )
            else:
                initial_score = self._evaluate_prompt(
                    prompt=prompt,  # type: ignore[arg-type]
                    optimization_id=optimization_id,
                    dataset=evaluation_dataset,
                    metric=metric,
                    n_samples=n_samples,
                    experiment_config=experiment_config,
                    use_full_dataset=n_samples is None,
                    verbose=self.verbose,
                    mcp_config=mcp_config,
                )
            best_score = initial_score
            best_prompt: Any = current_prompt
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

                # Calculate how many prompts to generate this round
                prompts_this_round = min(
                    self.prompts_per_round, max_trials - trials_used
                )

                # Create a set of candidate prompts
                is_synthesis_round = (
                    round_num >= self.synthesis_start_round
                    and (round_num - self.synthesis_start_round)
                    % self.synthesis_round_interval
                    == 0
                )

                if is_bundle:
                    generator: Any = self._generate_agent_bundle_candidates
                    generator_kwargs = dict(candidate_generator_kwargs or {})
                    # Strip evaluation-only params
                    for _key in (
                        "run_bundle_fn",
                        "bundle_plan",
                        "bundle_agent_class",
                        "bundle_agent_kwargs",
                    ):
                        generator_kwargs.pop(_key, None)
                    generator_kwargs.pop("bundle_agent_kwargs", None)
                    prompts_this_round = min(
                        self.prompts_per_round, max_trials - trials_used
                    )
                    is_synthesis_round = False
                else:
                    if is_synthesis_round and not candidate_generator:
                        # Synthesis round: combine top performers into comprehensive prompts
                        logger.info(
                            "Combining top performers into comprehensive prompts"
                        )

                        generator = self._generate_synthesis_prompts
                        # Synthesis doesn't use patterns
                        generator_kwargs = {}

                        # Synthesis creates a fixed, small number of prompts
                        prompts_this_round = min(
                            self.synthesis_prompts_per_round, max_trials - trials_used
                        )
                    else:
                        # Regular Round
                        generator = (
                            candidate_generator or self._generate_candidate_prompts
                        )
                        generator_kwargs = dict(candidate_generator_kwargs or {})

                        # Add patterns to generator kwargs for injection
                        if self.hall_of_fame:
                            patterns_to_inject = (
                                self.hall_of_fame.get_patterns_for_injection(n=3)
                            )
                            if patterns_to_inject:
                                generator_kwargs["winning_patterns"] = (
                                    patterns_to_inject
                                )
                                logger.debug(
                                    f"Injecting {len(patterns_to_inject)} patterns into generation"
                                )

                try:
                    _agent_metadata: list[dict[str, dict[str, str | None]]] | None = (
                        None
                    )
                    if is_bundle:
                        bundle_generation = generator(
                            current_prompts=best_prompt,  # type: ignore[arg-type]
                            best_score=best_score,
                            round_num=round_num,
                            previous_rounds=rounds,
                            metric=metric,
                            optimization_id=optimization_id,
                            project_name=self.project_name,
                            **generator_kwargs,
                        )
                        if (
                            isinstance(bundle_generation, tuple)
                            and len(bundle_generation) == 2
                        ):
                            prompts_part = bundle_generation[0]
                            _agent_metadata = bundle_generation[1]  # type: ignore[assignment]
                            if isinstance(prompts_part, list):
                                candidate_prompts = prompts_part
                            else:
                                candidate_prompts = [prompts_part]
                        else:
                            candidate_prompts = [bundle_generation]  # type: ignore[list-item]
                        candidate_prompts = candidate_prompts[:prompts_this_round]
                    else:
                        candidate_prompts = generator(
                            project_name=self.project_name,
                            current_prompt=best_prompt,  # type: ignore[arg-type]
                            best_score=best_score,
                            round_num=round_num,
                            previous_rounds=rounds,
                            metric=metric,
                            optimization_id=optimization_id,
                            **generator_kwargs,
                        )
                        candidate_prompts = candidate_prompts[:prompts_this_round]
                except Exception as e:
                    if isinstance(e, (BadRequestError, StructuredOutputParsingError)):
                        raise
                    round_reporter.failed_to_generate(prompts_this_round, str(e))

                    # If synthesis fails, fall back to regular generation
                    if is_synthesis_round:
                        logger.warning(
                            "Synthesis failed, falling back to regular candidate generation"
                        )
                        try:
                            generator = self._generate_candidate_prompts
                            generator_kwargs = {}
                            prompts_this_round = self.prompts_per_round
                            candidate_prompts = generator(
                                project_name=self.project_name,
                                current_prompt=best_prompt,
                                best_score=best_score,
                                round_num=round_num,
                                previous_rounds=rounds,
                                metric=metric,
                                optimization_id=optimization_id,
                                **generator_kwargs,
                            )
                            candidate_prompts = candidate_prompts[:prompts_this_round]
                        except Exception as fallback_e:
                            # If fallback also fails, break to prevent infinite loop
                            round_reporter.failed_to_generate(
                                prompts_this_round, str(fallback_e)
                            )
                            trials_used += prompts_this_round
                            break
                    else:
                        # Regular generation failed - break to prevent infinite loop
                        trials_used += prompts_this_round
                        break

                # Step 2. Score each candidate prompt
                prompt_scores: list[tuple[Any, float]] = []
                for candidate_count, prompt in enumerate(candidate_prompts):
                    with reporting.display_prompt_candidate_scoring_report(
                        verbose=self.verbose
                    ) as eval_report:
                        display_candidate = (
                            next(iter(prompt.values()))
                            if is_bundle and isinstance(prompt, dict)
                            else prompt
                        )
                        eval_report.set_generated_prompts(
                            candidate_count, display_candidate
                        )

                        candidate_prompt = prompt.copy()

                        try:
                            if is_bundle:
                                prompt_score = self._evaluate_bundle(
                                    bundle_prompts=candidate_prompt,  # type: ignore[arg-type]
                                    dataset=evaluation_dataset,
                                    metric=metric,
                                    n_samples=n_samples,
                                    run_bundle_fn=candidate_generator_kwargs.get(
                                        "run_bundle_fn"
                                    )
                                    if candidate_generator_kwargs
                                    else None,  # type: ignore[arg-type]
                                    bundle_plan=candidate_generator_kwargs.get(
                                        "bundle_plan"
                                    )
                                    if candidate_generator_kwargs
                                    else None,  # type: ignore[arg-type]
                                    bundle_agent_class=candidate_generator_kwargs.get(
                                        "bundle_agent_class"
                                    )
                                    if candidate_generator_kwargs
                                    else None,  # type: ignore[arg-type]
                                    bundle_agent_kwargs=candidate_generator_kwargs.get(
                                        "bundle_agent_kwargs"
                                    )
                                    if candidate_generator_kwargs
                                    else None,  # type: ignore[arg-type]
                                )
                            else:
                                prompt_score = self._evaluate_prompt(
                                    prompt=candidate_prompt,  # type: ignore[arg-type]
                                    optimization_id=optimization_id,
                                    dataset=evaluation_dataset,
                                    metric=metric,
                                    n_samples=n_samples,
                                    use_full_dataset=False,
                                    experiment_config=experiment_config,
                                    verbose=self.verbose,
                                    mcp_config=mcp_config,
                                )

                            eval_report.set_final_score(best_score, prompt_score)
                            if not is_bundle:
                                trials_used += 1
                        except Exception as exc:
                            logger.warning(
                                "Failed evaluating agent; continuing...", exc_info=exc
                            )
                            prompt_score = 0

                    prompt_scores.append((prompt, prompt_score))

                # Step 3. Identify potential improvements
                if not prompt_scores:
                    logger.warning(
                        "No prompts were successfully evaluated in this round"
                    )
                    break

                prompt_scores.sort(key=lambda x: x[1], reverse=True)
                best_candidate_this_round, best_cand_score_avg = prompt_scores[0]
                improvement = self._calculate_improvement(
                    best_cand_score_avg, best_score
                )
                round_reporter.round_end(round_num, best_cand_score_avg, best_score)

                # Add best candidate to hall of fame if qualified
                if self.hall_of_fame and best_cand_score_avg > 0 and not is_bundle:
                    from .ops.halloffame_ops import HallOfFameEntry

                    best_candidate_chat = cast(
                        chat_prompt.ChatPrompt, best_candidate_this_round
                    )
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
                    current_best_prompt=best_prompt,
                    current_best_score=best_score,
                    best_prompt_overall=best_prompt,
                    evaluated_candidates=prompt_scores,
                    previous_best_score=previous_best_score,
                    improvement_this_round=improvement,
                )
                rounds.append(round_data)
                self._add_to_history(round_data)

                if best_cand_score_avg > best_score:
                    best_score = best_cand_score_avg
                    best_prompt = best_candidate_this_round

                # Increment counters
                if is_bundle:
                    trials_used += 1
                round_num += 1

        if tool_panel_style and (not is_bundle) and getattr(best_prompt, "tools", None):
            description = (
                best_prompt.tools[0].get("function", {}).get("description", "")
                if best_prompt.tools
                else ""
            )
            if description.strip():
                reporting.display_tool_description(
                    description.strip(),
                    "Final tool description",
                    tool_panel_style,
                )

        if is_bundle and isinstance(best_prompt, dict):
            bundle_messages = {
                name: prompt.get_messages() for name, prompt in best_prompt.items()
            }
            first_prompt = next(iter(best_prompt.values()))
            best_prompt_messages = first_prompt.get_messages()
            best_tools = getattr(first_prompt, "tools", None)
        else:
            bundle_messages = None
            best_prompt_chat = cast(chat_prompt.ChatPrompt, best_prompt)
            best_prompt_messages = (
                best_prompt_chat.get_messages() if best_prompt_chat is not None else []
            )
            best_tools = (
                getattr(best_prompt_chat, "tools", None) if best_prompt_chat else None
            )

        reporting.display_result(
            initial_score,
            best_score,
            best_prompt_messages,
            verbose=self.verbose,
            tools=best_tools,
        )

        initial_prompt_messages = (
            initial_prompt.get_messages()
            if (initial_prompt is not None and not isinstance(initial_prompt, dict))
            else (
                next(iter(initial_prompt.values())).get_messages()
                if isinstance(initial_prompt, dict) and initial_prompt
                else []
            )
        )

        return self._create_result(
            metric,
            initial_prompt=initial_prompt_messages,
            best_prompt=best_prompt_messages,
            best_score=best_score,
            initial_score=initial_score,
            rounds=rounds,
            dataset_id=dataset_id,
            optimization_id=optimization_id,
            best_tools=best_tools,
            final_bundle_prompts=bundle_messages,
            best_bundle_prompts_obj=best_prompt
            if is_bundle and isinstance(best_prompt, dict)
            else None,
        )

    def _calculate_improvement(
        self, current_score: float, previous_score: float
    ) -> float:
        """Calculate the improvement percentage between scores (delegates to ops)."""
        return result_ops.calculate_improvement(current_score, previous_score)

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
        return result_ops.create_round_data(
            round_num=round_num,
            current_best_prompt=current_best_prompt,
            current_best_score=current_best_score,
            best_prompt_overall=best_prompt_overall,
            evaluated_candidates=evaluated_candidates,
            previous_best_score=previous_best_score,
            improvement_this_round=improvement_this_round,
        )

    def _create_result(
        self,
        metric: Callable,
        initial_prompt: list[dict[str, str]],
        best_prompt: list[dict[str, str]],
        best_score: float,
        initial_score: float,
        rounds: list[OptimizationRound],
        dataset_id: str | None,
        optimization_id: str | None,
        best_tools: list[dict[str, Any]] | None,
        final_bundle_prompts: dict[str, list[dict[str, Any]]] | None = None,
        best_bundle_prompts_obj: dict[str, chat_prompt.ChatPrompt] | None = None,
    ) -> OptimizationResult:
        """Create the final OptimizationResult object (delegates to ops)."""
        return result_ops.create_result(
            optimizer_class_name=self.__class__.__name__,
            metric=metric,
            initial_prompt=initial_prompt,
            best_prompt=best_prompt,
            best_score=best_score,
            initial_score=initial_score,
            rounds=rounds,
            dataset_id=dataset_id,
            optimization_id=optimization_id,
            best_tools=best_tools,
            llm_call_counter=self.llm_call_counter,
            tool_call_counter=self.tool_call_counter,
            model=self.model,
            model_parameters=self.model_parameters,
            extract_tool_prompts_fn=self._extract_tool_prompts,
            final_bundle_prompts=final_bundle_prompts,
            best_bundle_prompts_obj=best_bundle_prompts_obj,
        )

    def _get_task_context(self, metric: Callable) -> tuple[str, int]:
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

    def _generate_candidate_prompts(
        self,
        current_prompt: chat_prompt.ChatPrompt,
        best_score: float,
        round_num: int,
        previous_rounds: list[OptimizationRound],
        metric: Callable,
        optimization_id: str | None = None,
        project_name: str | None = None,
        winning_patterns: list[str] | None = None,
    ) -> list[chat_prompt.ChatPrompt]:
        """Generate candidate prompts using meta-prompting (delegates to ops)."""
        return candidate_ops.generate_candidate_prompts(
            optimizer=self,
            current_prompt=current_prompt,
            best_score=best_score,
            round_num=round_num,
            previous_rounds=previous_rounds,
            metric=metric,
            build_history_context_fn=self._build_history_context,
            get_task_context_fn=self._get_task_context,
            optimization_id=optimization_id,
            project_name=project_name,
            winning_patterns=winning_patterns,
        )

    def _generate_agent_bundle_candidates(
        self,
        current_prompts: dict[str, chat_prompt.ChatPrompt],
        best_score: float,
        round_num: int,
        previous_rounds: list[OptimizationRound],
        metric: Callable,
        optimization_id: str | None = None,
        project_name: str | None = None,
        winning_patterns: list[str] | None = None,
        mcp_config: MCPExecutionConfig | None = None,
        **_: Any,
    ) -> tuple[
        list[dict[str, chat_prompt.ChatPrompt]],
        list[dict[str, dict[str, str | None]]],
    ]:
        """
        Generate updated prompts for a bundle of named agents in one meta-prompt pass.

        MCP-enabled flows are explicitly disabled for this path.
        """
        return candidate_ops.generate_agent_bundle_candidates(
            optimizer=self,
            current_prompts=current_prompts,
            best_score=best_score,
            round_num=round_num,
            previous_rounds=previous_rounds,
            metric=metric,
            build_history_context_fn=self._build_history_context,
            get_task_context_fn=self._get_task_context,
            optimization_id=optimization_id,
            project_name=project_name,
            winning_patterns=winning_patterns,
            mcp_config=mcp_config,
        )

    def _generate_mcp_candidate_prompts(
        self,
        current_prompt: chat_prompt.ChatPrompt,
        best_score: float,
        round_num: int,
        previous_rounds: list[OptimizationRound],
        metric: Callable,
        tool_segment_id: str,
        tool_name: str,
        optimization_id: str | None = None,
        project_name: str | None = None,
        panel_style: str = "bright_magenta",
    ) -> list[chat_prompt.ChatPrompt]:
        """Generate MCP tool description candidate prompts (delegates to ops)."""
        return candidate_ops.generate_mcp_candidate_prompts(
            optimizer=self,
            current_prompt=current_prompt,
            best_score=best_score,
            round_num=round_num,
            previous_rounds=previous_rounds,
            metric=metric,
            tool_segment_id=tool_segment_id,
            tool_name=tool_name,
            build_history_context_fn=self._build_history_context,
            optimization_id=optimization_id,
            project_name=project_name,
            panel_style=panel_style,
        )

    def _generate_synthesis_prompts(
        self,
        current_prompt: chat_prompt.ChatPrompt,
        best_score: float,
        round_num: int,
        previous_rounds: list[OptimizationRound],
        metric: Callable,
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

    def _evaluate_bundle(
        self,
        bundle_prompts: dict[str, chat_prompt.ChatPrompt],
        dataset: Dataset,
        metric: Callable,
        n_samples: int | None = None,
        run_bundle_fn: Callable[
            [dict[str, chat_prompt.ChatPrompt], dict[str, Any]], dict[str, Any]
        ]
        | None = None,
        bundle_plan: list[str] | Callable[[dict[str, Any]], list[str]] | None = None,
        bundle_agent_class: type[Any] | None = None,
        bundle_agent_kwargs: dict[str, Any] | None = None,
    ) -> float:
        """
        Evaluate a bundle of prompts (agents) using either a user-supplied runner or simple sequenced agent.

        This method evaluates a collection of prompts representing different agents in a multi-agent system.
        It supports three evaluation modes:
        1. Custom runner function (run_bundle_fn)
        2. Bundle agent class with a plan
        3. Simple sequential execution (fallback)

        Args:
            bundle_prompts: Dictionary mapping agent names to their ChatPrompt configurations.
                Each prompt represents a different agent in the bundle.
            dataset: Opik Dataset containing evaluation examples. Each item is passed to the
                bundle execution for scoring.
            metric: Evaluation function that takes (dataset_item, llm_output, [trace]) and returns
                a score (float). The trace dict contains execution context from agent runs.
            n_samples: Number of dataset items to use for evaluation. If None, uses full dataset.
            run_bundle_fn: Optional custom function to execute the bundle. Must accept
                (bundle_prompts, dataset_item) and return dict with 'final_output' and 'trace'.
            bundle_plan: Optional execution plan for bundle agents. Can be a static list of
                agent names or a callable that generates the plan dynamically from dataset items.
            bundle_agent_class: Optional agent class for bundle execution. Must implement
                run_bundle(dataset_item) method and accept prompts/plan in constructor.
            bundle_agent_kwargs: Optional extra kwargs for the bundle agent constructor
                (e.g., search_fn, model, model_parameters).

        Returns:
            Average score across all evaluated dataset items. Returns 0.0 if no items scored.

        Note:
            - Uses parallel evaluation via ThreadPoolExecutor when n_threads > 1
            - Falls back to sequential evaluation if parallel execution fails
            - Collects trace context from bundle runs to pass to metrics that support it
        """

        def _bundle_system_context() -> str:
            if not isinstance(bundle_prompts, dict):
                return ""
            parts: list[str] = []
            for prompt in bundle_prompts.values():
                system_msg = getattr(prompt, "system", "") or ""
                if not system_msg:
                    messages = prompt.get_messages()
                    system_msg = next(
                        (
                            m.get("content", "")
                            for m in messages
                            if m.get("role") == "system"
                        ),
                        "",
                    )
                if system_msg:
                    parts.append(system_msg)
            return " ".join(parts).strip()

        # Parallel-friendly task wrapper so we can use task_evaluator with num_threads.
        def _evaluated_task(dataset_item: dict[str, Any]) -> dict[str, Any]:
            if callable(run_bundle_fn):
                run_result = run_bundle_fn(bundle_prompts, dataset_item)
            elif bundle_agent_class is not None and callable(bundle_agent_class):
                agent: Any = bundle_agent_class(
                    prompts=bundle_prompts,
                    plan=bundle_plan,
                    project_name=self.project_name,
                    **(bundle_agent_kwargs or {}),
                )
                run_result = agent.run_bundle(dataset_item)  # type: ignore[attr-defined]
            else:
                # Simple sequenced runner over provided prompts in order
                trace_steps: list[dict[str, Any]] = []
                final_output = ""
                for name, prompt in bundle_prompts.items():
                    messages = prompt.get_messages(dataset_item)
                    invoker = getattr(prompt, "invoke", None)
                    step_output = ""
                    if callable(invoker):
                        step_output = invoker(messages)
                    trace_steps.append(
                        {"agent": name, "messages": messages, "output": step_output}
                    )
                    final_output = step_output
                run_result = {
                    "final_output": final_output,
                    "trace": {"steps": trace_steps},
                }

            if not isinstance(run_result, dict):
                raise ValueError("bundle runner must return a dict with 'final_output'")

            final_output_val = run_result.get("final_output")
            if final_output_val is None:
                final_output_val = run_result.get("output")
            final_output_str: str = (
                final_output_val if isinstance(final_output_val, str) else ""
            )
            trace = run_result.get("trace") or {"system": _bundle_system_context()}
            return {"llm_output": final_output_str, "trace": trace}

        # Parallel evaluation with trace preservation; falls back to sequential if needed.
        # FIXME(opik): task_evaluator.evaluate_with_result would be cleaner here, but its
        # current interface strips trace context and mismatches some bundle metrics. Until
        # task_evaluator grows a trace-aware hook or a pluggable runner, we keep this
        # local thread pool for bundles.
        # TODO(opik): add per-task log prefixes (thread-aware) so interleaved tool/LLM logs
        # remain readable during parallel bundle evals.
        items = dataset.get_items()
        if n_samples is not None:
            items = items[:n_samples]
        if not items:
            return 0.0

        def _score_item(item: dict[str, Any]) -> float:
            run_result = _evaluated_task(item)
            final_output = run_result.get("llm_output", "")
            trace = run_result.get("trace") or {}
            try:
                score_val = metric(item, final_output, trace)  # type: ignore[misc]
            except TypeError:
                score_val = metric(item, final_output)  # type: ignore[misc]
            if hasattr(score_val, "value"):
                score_val = getattr(score_val, "value", score_val)
            return float(score_val)

        scores_list: list[float] = []
        n_threads = max(getattr(self, "n_threads", 1) or 1, 1)
        if n_threads > 1 and len(items) > 1:
            try:
                with ThreadPoolExecutor(max_workers=n_threads) as executor:
                    futures = {
                        executor.submit(_score_item, item): item for item in items
                    }
                    for future in as_completed(futures):
                        try:
                            scores_list.append(future.result())
                        except Exception as exc:
                            logger.warning("Bundle scoring failed for an item: %s", exc)
            except Exception as exc:
                logger.warning(
                    "Parallel bundle evaluation failed, falling back: %s", exc
                )
                scores_list.clear()

        if not scores_list:
            for item in items:
                try:
                    scores_list.append(_score_item(item))
                except Exception as exc:
                    logger.warning(
                        "Sequential bundle scoring failed for an item: %s", exc
                    )

        return sum(scores_list) / len(scores_list) if scores_list else 0.0


__all__ = ["MetaPromptOptimizer", "_sync_tool_description_in_system"]
