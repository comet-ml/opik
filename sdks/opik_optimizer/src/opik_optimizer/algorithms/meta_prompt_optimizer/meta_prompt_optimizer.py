import copy
import json
import logging
import textwrap
from typing import Any, cast
from collections.abc import Callable

import opik
from opik import Dataset, opik_context
from opik.environment import get_tqdm_for_current_environment

from opik_optimizer import task_evaluator

from ... import _throttle, helpers
from ...base_optimizer import BaseOptimizer, OptimizationRound
from ...api_objects import chat_prompt
from ...optimization_result import OptimizationResult
from ...optimizable_agent import OptimizableAgent
from . import reporting
import re
from ... import _llm_calls
from ...mcp_utils.mcp import PROMPT_TOOL_FOOTER, PROMPT_TOOL_HEADER
from ...mcp_utils.mcp_workflow import (
    MCPExecutionConfig,
    MCPSecondPassCoordinator,
    extract_tool_arguments,
)
from ...utils.prompt_segments import apply_segment_updates, extract_prompt_segments

tqdm = get_tqdm_for_current_environment()

# Set up logging
logger = logging.getLogger(__name__)  # Gets logger configured by setup_logging

_rate_limiter = _throttle.get_rate_limiter_for_current_opik_installation()


def _sync_tool_description_in_system(prompt: chat_prompt.ChatPrompt) -> None:
    if not prompt.system or not getattr(prompt, "tools", None):
        return

    description = (
        prompt.tools[0].get("function", {}).get("description") if prompt.tools else None
    )
    if not description:
        return

    tool_name = (
        prompt.tools[0].get("function", {}).get("name") if prompt.tools else None
    )

    system_text = cast(str, prompt.system)
    if PROMPT_TOOL_HEADER not in system_text or PROMPT_TOOL_FOOTER not in system_text:
        return

    start = system_text.index(PROMPT_TOOL_HEADER) + len(PROMPT_TOOL_HEADER)
    end = system_text.index(PROMPT_TOOL_FOOTER)
    description_text = description.strip()
    system_text = (
        system_text[:start] + "\n" + description_text + "\n" + system_text[end:]
    )
    prompt.system = system_text

    if tool_name:
        pattern = rf"(-\s*{re.escape(tool_name)}:\s)(.*)"

        def _tool_section_replacer(match: re.Match[str]) -> str:
            return f"{match.group(1)}{description_text}"

        system_text = re.sub(
            pattern,
            _tool_section_replacer,
            system_text,
            count=1,
            flags=re.MULTILINE,
        )
        prompt.system = system_text


class MetaPromptOptimizer(BaseOptimizer):
    """
    Meta-Prompt Optimizer that uses LLM-based meta-reasoning to iteratively improve prompts.

    This optimizer uses an LLM to analyze prompt performance and generate improved variations
    by reasoning about what changes would be most effective. It's particularly useful for:
    - Ensuring prompts follow best practices
    - Refining prompts for clarity and effectiveness
    - Optimizing prompts for specific evaluation metrics
    - Improving prompts based on performance feedback

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
        n_threads: Number of parallel threads for prompt evaluation
        verbose: Controls internal logging/progress bars (0=off, 1=on)
        seed: Random seed for reproducibility
    """

    # --- Constants for Default Configuration ---
    DEFAULT_ROUNDS = 3
    DEFAULT_PROMPTS_PER_ROUND = 4

    # --- Reasoning System Prompt ---
    _REASONING_SYSTEM_PROMPT = """You are an expert prompt engineer. Your task is to improve prompts for any type of task.

        Focus on making the prompt more effective by:
        1. Being clear and specific about what is expected
        2. Providing necessary context and constraints
        3. Guiding the model to produce the desired output format
        4. Removing ambiguity and unnecessary elements
        5. Maintaining conciseness while being complete

        Instructions:
        1. If there is a system prompt, prioritize adding instructions there if and only if it makes sense.
        2. DO NOT add any variables or parameters to the prompt you are editing.
        3. You can reuse variables that already exist in the prompt.

        Return a JSON array of prompts with the following structure. Make sure to return a valid
        JSON object with correct use of double quotes and single quotes. JSON keys should be
        double-quoted:
        {
            "prompts": [
                {
                    "prompt": [{"role": "<role>", "content": "<content>"}],
                    "improvement_focus": "what aspect this prompt improves",
                    "reasoning": "why this improvement should help"
                },
                {
                    "prompt": [{"role": "<role>", "content": "<content>"}],
                    "improvement_focus": "what aspect this prompt improves",
                    "reasoning": "why this improvement should help"
                }
            ]
        }"""

    def __init__(
        self,
        model: str = "gpt-4o",
        model_parameters: dict[str, Any] | None = None,
        prompts_per_round: int = DEFAULT_PROMPTS_PER_ROUND,
        enable_context: bool = True,
        n_threads: int = 12,
        verbose: int = 1,
        seed: int = 42,
        name: str | None = None,
    ) -> None:
        super().__init__(
            model=model,
            verbose=verbose,
            seed=seed,
            model_parameters=model_parameters,
            name=name,
        )
        self.prompts_per_round = prompts_per_round
        self.n_threads = n_threads
        self.dataset: Dataset | None = None
        self.enable_context = enable_context
        logger.debug(f"Initialized MetaPromptOptimizer with model={model}")
        logger.debug(f"Prompts/round: {prompts_per_round}")

    def get_optimizer_metadata(self) -> dict[str, Any]:
        return {
            "prompts_per_round": self.prompts_per_round,
            "enable_context": self.enable_context,
        }

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
        """
        Args:
            dataset: Opik Dataset to evaluate the prompt on
            metric: Metric functions
            use_full_dataset: Whether to use the full dataset or a subset
            experiment_config: Optional configuration for the experiment, useful to log additional metadata
            n_samples: Optional number of items to test in the dataset
            optimization_id: Optional ID of the optimization
            verbose: Controls internal logging/progress bars (0=off, 1=on).
        Returns:
            float: The evaluation score
        """
        # Calculate subset size for trials
        if not use_full_dataset:
            total_items = len(dataset.get_items())
            if n_samples is not None:
                if n_samples > total_items:
                    logger.warning(
                        f"Requested n_samples ({n_samples}) is larger than dataset size ({total_items}). Using full dataset."
                    )
                    subset_size = None
                else:
                    subset_size = n_samples
                    logger.debug(f"Using specified n_samples: {subset_size} items")
            else:
                # Calculate 20% of total, but no more than 20 items and no more than total items
                subset_size = min(total_items, min(20, max(10, int(total_items * 0.2))))
                logger.debug(
                    f"Using automatic subset size calculation: {subset_size} items (20% of {total_items} total items)"
                )
        else:
            subset_size = None  # Use all items for final checks
            logger.debug("Using full dataset for evaluation")

        configuration_updates = helpers.drop_none(
            {
                "n_samples": subset_size,
                "use_full_dataset": use_full_dataset,
            }
        )
        meta_metadata = helpers.drop_none(
            {
                "optimization_id": optimization_id,
                "stage": "trial_evaluation" if not use_full_dataset else "final_eval",
            }
        )
        experiment_config = self._prepare_experiment_config(
            prompt=prompt,
            dataset=dataset,
            metric=metric,
            experiment_config=experiment_config,
            configuration_updates=configuration_updates,
            additional_metadata={"meta_prompt": meta_metadata}
            if meta_metadata
            else None,
        )

        def llm_task(dataset_item: dict[str, Any]) -> dict[str, str]:
            new_prompt = prompt.copy()
            messages = new_prompt.get_messages(dataset_item)
            new_prompt.set_messages(messages)
            agent = self.agent_class(new_prompt)

            if mcp_config is not None:
                coordinator = mcp_config.coordinator
                coordinator.reset()
                try:
                    logger.debug(
                        "Calling MCP-enabled LLM with tool access; prompt length=%s",
                        sum(len(msg["content"]) for msg in messages),
                    )
                    raw_model_output = agent.llm_invoke(
                        messages=messages,
                        seed=self.seed,
                        allow_tool_use=True,
                    )
                except Exception as exc:
                    logger.error("Error during MCP first pass: %s", exc)
                    raise

                second_pass_messages = coordinator.build_second_pass_messages(
                    base_messages=messages,
                    dataset_item=dataset_item,
                )

                if second_pass_messages is None and mcp_config.fallback_invoker:
                    fallback_args = mcp_config.fallback_arguments(dataset_item)
                    if fallback_args:
                        logger.debug(
                            "MCP fallback triggered for tool %s with args=%s",
                            mcp_config.tool_name,
                            fallback_args,
                        )
                        summary_override = mcp_config.fallback_invoker(fallback_args)
                        second_pass_messages = coordinator.build_second_pass_messages(
                            base_messages=messages,
                            dataset_item=dataset_item,
                            summary_override=summary_override,
                        )

                if second_pass_messages is not None:
                    logger.debug(
                        "Executing MCP second pass with %d messages",
                        len(second_pass_messages),
                    )
                    final_response = agent.llm_invoke(
                        messages=second_pass_messages,
                        seed=self.seed,
                        allow_tool_use=mcp_config.allow_tool_use_on_second_pass,
                    )
                else:
                    final_response = raw_model_output

                cleaned_model_output = final_response.strip()
            else:
                try:
                    logger.debug(
                        f"Calling LLM with prompt length: {sum(len(msg['content']) for msg in messages)}"
                    )
                    raw_model_output = agent.invoke(messages)
                    logger.debug(f"LLM raw response length: {len(raw_model_output)}")
                    logger.debug(f"LLM raw output: {raw_model_output}")
                except Exception as e:
                    logger.error(f"Error calling model with prompt: {e}")
                    logger.error(f"Failed prompt: {messages}")
                    logger.error(
                        f"Prompt length: {sum(len(msg['content']) for msg in messages)}"
                    )
                    raise

                cleaned_model_output = raw_model_output.strip()

            # Add tags to trace for optimization tracking
            if self.current_optimization_id:
                opik_context.update_current_trace(
                    tags=[self.current_optimization_id, "Evaluation"]
                )

            result = {
                "llm_output": cleaned_model_output,
            }
            return result

        # Use dataset's get_items with limit for sampling
        logger.debug(
            f"Starting evaluation with {subset_size if subset_size else 'all'} samples for metric: {getattr(metric, '__name__', str(metric))}"
        )
        score = task_evaluator.evaluate(
            dataset=dataset,
            metric=metric,
            evaluated_task=llm_task,
            dataset_item_ids=dataset_item_ids,
            num_threads=self.n_threads,
            project_name=self.project_name,
            n_samples=subset_size,  # Use subset_size for trials, None for full dataset
            experiment_config=experiment_config,
            optimization_id=optimization_id,
            verbose=self.verbose,
        )
        logger.debug(f"Evaluation score: {score:.4f}")
        return score

    def optimize_prompt(
        self,
        prompt: chat_prompt.ChatPrompt,
        dataset: Dataset,
        metric: Callable,
        experiment_config: dict | None = None,
        n_samples: int | None = None,
        auto_continue: bool = False,
        agent_class: type[OptimizableAgent] | None = None,
        project_name: str = "Optimization",
        optimization_id: str | None = None,
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
        # Use base class validation and setup methods
        self._validate_optimization_inputs(prompt, dataset, metric)
        self.agent_class = self._setup_agent_class(prompt, agent_class)

        # Set project name from parameter
        self.project_name = project_name

        total_items = len(dataset.get_items())
        if n_samples is not None and n_samples > total_items:
            logger.warning(
                f"Requested n_samples ({n_samples}) is larger than dataset size ({total_items}). Using full dataset."
            )
            n_samples = None

        optimization = None
        try:
            optimization = self.opik_client.create_optimization(
                dataset_name=dataset.name,
                objective_name=getattr(metric, "__name__", str(metric)),
                name=self.name,
                metadata=self._build_optimization_config(),
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
            dataset_id=dataset.id,
            verbose=self.verbose,
        )
        reporting.display_configuration(
            messages=prompt.get_messages(),
            optimizer_config={
                "optimizer": self.__class__.__name__,
                "max_trials": max_trials,
                "prompts_per_round": self.prompts_per_round,
                "n_samples": n_samples,
                "auto_continue": auto_continue,
            },
            verbose=self.verbose,
            tools=getattr(prompt, "tools", None),
        )

        try:
            optimization_id = optimization.id if optimization is not None else None
            result = self._optimize_prompt(
                optimization_id=optimization_id,
                prompt=prompt,
                dataset=dataset,
                metric=metric,
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
        prompt: chat_prompt.ChatPrompt,
        dataset: Dataset,
        metric: Callable,
        experiment_config: dict | None,
        max_trials: int,
        n_samples: int | None,
        auto_continue: bool,
        mcp_config: MCPExecutionConfig | None = None,
        candidate_generator: Callable[..., list[chat_prompt.ChatPrompt]] | None = None,
        candidate_generator_kwargs: dict[str, Any] | None = None,
        tool_panel_style: str = "bright_magenta",
    ) -> OptimizationResult:
        self.auto_continue = auto_continue
        self.dataset = dataset
        self.prompt = prompt
        self._reset_counters()  # Reset counters for run
        initial_prompt = prompt

        current_prompt = prompt
        with reporting.display_evaluation(verbose=self.verbose) as baseline_reporter:
            initial_score = self._evaluate_prompt(
                prompt,
                optimization_id=optimization_id,
                dataset=dataset,
                metric=metric,
                n_samples=n_samples,
                experiment_config=experiment_config,
                use_full_dataset=n_samples is None,
                verbose=self.verbose,
                mcp_config=mcp_config,
            )
            best_score = initial_score
            best_prompt = current_prompt
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

                # Calculate how many prompts to generate this round
                prompts_this_round = min(
                    self.prompts_per_round, max_trials - trials_used
                )

                # Step 1. Create a set of candidate prompts
                generator = candidate_generator or self._generate_candidate_prompts
                generator_kwargs = dict(candidate_generator_kwargs or {})

                try:
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
                    # Limit to prompts_this_round
                    candidate_prompts = candidate_prompts[:prompts_this_round]
                except Exception as e:
                    round_reporter.failed_to_generate(prompts_this_round, e)
                    round_num += 1
                    continue

                # Step 2. Score each candidate prompt
                prompt_scores: list[tuple[chat_prompt.ChatPrompt, float]] = []
                for candidate_count, prompt in enumerate(candidate_prompts):
                    with reporting.display_prompt_candidate_scoring_report(
                        verbose=self.verbose
                    ) as eval_report:
                        eval_report.set_generated_prompts(candidate_count, prompt)

                        candidate_prompt = prompt.copy()

                        try:
                            prompt_score = self._evaluate_prompt(
                                prompt=candidate_prompt,
                                optimization_id=optimization_id,
                                dataset=dataset,
                                metric=metric,
                                n_samples=n_samples,
                                use_full_dataset=False,
                                experiment_config=experiment_config,
                                verbose=self.verbose,
                                mcp_config=mcp_config,
                            )

                            eval_report.set_final_score(best_score, prompt_score)
                            trials_used += 1
                        except Exception:
                            logger.warning("Failed evaluating agent; continuing...")
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

                if improvement > 0:
                    best_score = best_cand_score_avg
                    best_prompt = best_candidate_this_round

                # Increment counters
                round_num += 1

        if tool_panel_style and getattr(best_prompt, "tools", None):
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

        reporting.display_result(
            initial_score,
            best_score,
            best_prompt.get_messages() if best_prompt is not None else [],
            verbose=self.verbose,
            tools=getattr(best_prompt, "tools", None) if best_prompt else None,
        )

        return self._create_result(
            metric,
            initial_prompt=(
                initial_prompt.get_messages() if initial_prompt is not None else []
            ),
            best_prompt=best_prompt.get_messages() if best_prompt is not None else [],
            best_score=best_score,
            initial_score=initial_score,
            rounds=rounds,
            dataset_id=dataset.id,
            optimization_id=optimization_id,
            best_tools=getattr(best_prompt, "tools", None) if best_prompt else None,
        )

    def _calculate_improvement(
        self, current_score: float, previous_score: float
    ) -> float:
        """Calculate the improvement percentage between scores."""
        return (
            (current_score - previous_score) / previous_score
            if previous_score > 0
            else 0
        )

    def _create_round_data(
        self,
        round_num: int,
        current_best_prompt: chat_prompt.ChatPrompt,
        current_best_score: float,
        best_prompt_overall: chat_prompt.ChatPrompt,
        evaluated_candidates: list[tuple[chat_prompt.ChatPrompt, float]],
        previous_best_score: float,
        improvement_this_round: float,
    ) -> OptimizationRound:
        """Create an OptimizationRound object with the current round's data."""
        generated_prompts_log: list[dict[str, Any]] = []
        for prompt, score in evaluated_candidates:
            improvement_vs_prev = self._calculate_improvement(
                score, previous_best_score
            )
            tool_entries: list[Any] = []
            if getattr(prompt, "tools", None):
                tool_entries = copy.deepcopy(list(prompt.tools or []))

            generated_prompts_log.append(
                {
                    "prompt": prompt.get_messages(),
                    "tools": tool_entries,
                    "score": score,
                    "improvement": improvement_vs_prev,
                }
            )

        return OptimizationRound(
            round_number=round_num + 1,
            current_prompt=current_best_prompt,
            current_score=current_best_score,
            generated_prompts=generated_prompts_log,
            best_prompt=best_prompt_overall,
            best_score=current_best_score,
            improvement=improvement_this_round,
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
    ) -> OptimizationResult:
        """Create the final OptimizationResult object."""
        details = {
            "final_prompt": best_prompt,
            "final_score": best_score,
            "rounds": rounds,
            "total_rounds": len(rounds),
            "metric_name": getattr(metric, "__name__", str(metric)),
            "model": self.model,
            "temperature": self.model_parameters.get("temperature"),
        }

        if best_tools:
            details["final_tools"] = best_tools

        tool_prompts = self._extract_tool_prompts(best_tools)

        return OptimizationResult(
            optimizer=self.__class__.__name__,
            prompt=best_prompt,
            score=best_score,
            initial_prompt=initial_prompt,
            initial_score=initial_score,
            metric_name=getattr(metric, "__name__", str(metric)),
            details=details,
            llm_calls=self.llm_call_counter,
            tool_calls=self.tool_call_counter,
            dataset_id=dataset_id,
            optimization_id=optimization_id,
            tool_prompts=tool_prompts,
        )

    def _get_task_context(self, metric: Callable) -> str:
        """Get task-specific context from the dataset and metric configuration."""
        if self.dataset is None:
            return ""

        try:
            # Try get_items() first as it's the preferred method
            items = self.dataset.get_items()
            sample = items[0]  # Get first sample
        except Exception as e:
            logger.warning(f"Could not get sample from dataset: {e}")

        # Describe Single Metric
        if sample is not None:
            metric_name = metric.__name__
            description = metric.__doc__ or "No description available."

            metrics_str = f"- {metric_name}: {description}"

            context = "\nTask Context:\n"
            context += f"Dataset fields (includes both input and optionally the expected output): {', '.join([x for x in sample.keys() if x != 'id'])}\n"
            context += f"Evaluation Metric:\n{metrics_str}\n"
            context += f"\nExample:\n{json.dumps(sample)}\n"

        return context

    def _generate_candidate_prompts(
        self,
        current_prompt: chat_prompt.ChatPrompt,
        best_score: float,
        round_num: int,
        previous_rounds: list[OptimizationRound],
        metric: Callable,
        optimization_id: str | None = None,
        project_name: str | None = None,
    ) -> list[chat_prompt.ChatPrompt]:
        """Generate candidate prompts using meta-prompting."""
        with reporting.display_candidate_generation_report(
            self.prompts_per_round, verbose=self.verbose
        ) as candidate_generation_report:
            logger.debug(f"\nGenerating candidate prompts for round {round_num + 1}")
            logger.debug(f"Generating from prompt: {current_prompt.get_messages()}")
            logger.debug(f"Current best score: {best_score:.4f}")

            history_context = self._build_history_context(previous_rounds)
            task_context_str = ""
            analysis_instruction = ""
            metric_focus_instruction = ""
            improvement_point_1 = ""

            if self.enable_context:
                task_context_str = self._get_task_context(metric=metric)
                analysis_instruction = "Analyze the example provided (if any), the metric description (if any), and the history of scores."
                metric_focus_instruction = (
                    f"Focus on improving the score for the metric: {metric.__name__}."
                )
                improvement_point_1 = "1. Be more specific and clear about expectations based on the metric and task."
                logger.debug(
                    "Task context and metric-specific instructions enabled for reasoning prompt."
                )
            else:
                analysis_instruction = "Analyze the history of scores and the current prompt's performance."
                metric_focus_instruction = "Focus on generating diverse and effective prompt variations based on the history."
                improvement_point_1 = "1. Be more specific and clear about expectations based on the task."
                logger.debug(
                    "Task context and metric-specific instructions disabled for reasoning prompt."
                )

            user_prompt = f"""Current prompt: {current_prompt.get_messages()}
            Current score: {best_score}
            {history_context}
            {task_context_str}

            {analysis_instruction}
            Generate {self.prompts_per_round} improved versions of this prompt.
            {metric_focus_instruction}
            Each version should aim to:
            {improvement_point_1}
            2. Provide necessary context and constraints (if applicable, without relying on disabled external context).
            3. Guide the model to produce the desired output format suitable for the task.
            4. Remove ambiguity and unnecessary elements.
            5. Maintain conciseness while being complete.

            Return a valid JSON array as specified."""

            try:
                # Prepare metadata for optimization algorithm call
                metadata_for_call: dict[str, Any] = {}
                if project_name:
                    metadata_for_call["project_name"] = project_name
                    metadata_for_call["opik"] = {"project_name": project_name}
                if optimization_id and "opik" in metadata_for_call:
                    metadata_for_call["opik"]["optimization_id"] = optimization_id
                metadata_for_call["optimizer_name"] = self.__class__.__name__
                metadata_for_call["opik_call_type"] = "optimization_algorithm"

                content = _llm_calls.call_model(
                    messages=[
                        {"role": "system", "content": self._REASONING_SYSTEM_PROMPT},
                        {"role": "user", "content": user_prompt},
                    ],
                    model=self.model,
                    model_parameters=self.model_parameters,
                    metadata=metadata_for_call,
                    optimization_id=optimization_id,
                )
                logger.debug(f"Raw response from reasoning model: {content}")

                # --- Robust JSON Parsing and Validation ---
                json_result = None
                try:
                    # Try direct JSON parsing
                    json_result = json.loads(content)
                except json.JSONDecodeError:
                    import re

                    json_match = re.search(r"\{.*\}", content, re.DOTALL)
                    if json_match:
                        try:
                            json_result = json.loads(json_match.group())
                        except json.JSONDecodeError as e:
                            raise ValueError(
                                f"Could not parse JSON extracted via regex: {e} - received: {json_match.group()}"
                            )
                    else:
                        raise ValueError(
                            f"No JSON object found in response via regex. - received: {content}"
                        )

                # Validate the parsed JSON structure
                if isinstance(json_result, list) and len(json_result) == 1:
                    json_result = json_result[0]

                if not isinstance(json_result, dict) or "prompts" not in json_result:
                    logger.debug(f"Parsed JSON content: {json_result}")
                    raise ValueError(
                        f"Parsed JSON is not a dictionary or missing 'prompts' key. - received: {json_result}"
                    )

                if not isinstance(json_result["prompts"], list):
                    logger.debug(f"Content of 'prompts': {json_result.get('prompts')}")
                    raise ValueError(
                        f"'prompts' key does not contain a list. - received: {json_result.get('prompts')}"
                    )

                # Extract and log valid prompts
                valid_prompts: list[chat_prompt.ChatPrompt] = []
                for item in json_result["prompts"]:
                    if (
                        isinstance(item, dict)
                        and "prompt" in item
                        and isinstance(item["prompt"], list)
                    ):
                        # NOTE: might be brittle
                        if current_prompt.user:
                            user_text = current_prompt.user
                        else:
                            if current_prompt.messages is not None:
                                user_text = current_prompt.messages[-1]["content"]
                            else:
                                raise Exception(
                                    "User content not found in chat-prompt!"
                                )

                        valid_prompts.append(
                            chat_prompt.ChatPrompt(
                                system=item["prompt"][0]["content"],
                                user=user_text,
                                tools=current_prompt.tools,
                                function_map=current_prompt.function_map,
                            )
                        )

                        # Log details
                        focus = item.get("improvement_focus", "N/A")
                        reasoning = item.get("reasoning", "N/A")
                        logger.debug(f"Generated prompt: {item['prompt']}")
                        logger.debug(f"  Improvement focus: {focus}")
                        logger.debug(f"  Reasoning: {reasoning}")
                    else:
                        logger.warning(
                            f"Skipping invalid prompt item structure in JSON response: {item}"
                        )

                if not valid_prompts:
                    raise ValueError(
                        "No valid prompts found in the parsed JSON response after validation."
                    )

                candidate_generation_report.set_generated_prompts()

                return valid_prompts
                # --- End Robust Parsing ---

            except Exception as e:
                raise ValueError(
                    f"Unexpected error during candidate prompt generation: {e}"
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
        segments = {
            segment.segment_id: segment
            for segment in extract_prompt_segments(current_prompt)
        }
        if tool_segment_id not in segments:
            raise ValueError(f"Tool segment '{tool_segment_id}' not found in prompt")

        target_segment = segments[tool_segment_id]
        current_description = target_segment.content
        tool_metadata = target_segment.metadata.get("raw_tool", {})

        history_context = self._build_history_context(previous_rounds)

        instruction = textwrap.dedent(
            f"""
            Current tool name: {tool_name}
            Current tool description:
            ---
            {current_description}
            ---

            Tool metadata (JSON):
            {json.dumps(tool_metadata, indent=2)}

            Current best score: {best_score:.4f}
            {history_context}

            Generate {self.prompts_per_round} improved descriptions for this tool.
            Each description should clarify expected input arguments and set explicit expectations
            for how the tool output must be used in the final response.
            Avoid changing unrelated parts of the prompt. Focus only on the description text for `{tool_name}`.

            Return a JSON object of the form:
            {{
              "prompts": [
                {{
                  "tool_description": "...",
                  "improvement_focus": "...",
                  "reasoning": "..."
                }}
              ]
            }}
            """
        ).strip()

        with reporting.display_candidate_generation_report(
            self.prompts_per_round, verbose=self.verbose
        ) as candidate_generation_report:
            try:
                # Prepare metadata for optimization algorithm call
                metadata_for_call_tools: dict[str, Any] = {}
                if project_name:
                    metadata_for_call_tools["project_name"] = project_name
                    metadata_for_call_tools["opik"] = {"project_name": project_name}
                if optimization_id and "opik" in metadata_for_call_tools:
                    metadata_for_call_tools["opik"]["optimization_id"] = optimization_id
                metadata_for_call_tools["optimizer_name"] = self.__class__.__name__
                metadata_for_call_tools["opik_call_type"] = "optimization_algorithm"

                content = _llm_calls.call_model(
                    messages=[
                        {"role": "system", "content": self._REASONING_SYSTEM_PROMPT},
                        {"role": "user", "content": instruction},
                    ],
                    model=self.model,
                    model_parameters=self.model_parameters,
                    metadata=metadata_for_call_tools,
                    optimization_id=optimization_id,
                )

                try:
                    json_result = json.loads(content)
                except json.JSONDecodeError:
                    import re

                    json_match = re.search(r"\{.*\}", content, re.DOTALL)
                    if not json_match:
                        raise ValueError("No JSON object found in reasoning output")
                    json_result = json.loads(json_match.group())

                prompts_payload = json_result.get("prompts")
                if not isinstance(prompts_payload, list):
                    raise ValueError("Reasoning output missing 'prompts' list")

                candidate_generation_report.set_generated_prompts()

                candidates: list[chat_prompt.ChatPrompt] = []
                for item in prompts_payload:
                    if not isinstance(item, dict):
                        continue
                    description = item.get("tool_description")
                    if not isinstance(description, str) or not description.strip():
                        continue

                    updated_prompt = apply_segment_updates(
                        current_prompt,
                        {tool_segment_id: description.strip()},
                    )
                    _sync_tool_description_in_system(updated_prompt)
                    if (
                        description.strip()
                        and description.strip() != current_description.strip()
                    ):
                        reporting.display_tool_description(
                            description.strip(),
                            f"Round {round_num + 1} tool description",
                            panel_style,
                        )
                    candidates.append(updated_prompt)

                if not candidates:
                    raise ValueError(
                        "Reasoning output did not produce valid tool descriptions"
                    )

                return candidates
            except Exception as exc:
                raise ValueError(f"Error generating MCP prompt candidates: {exc}")

    def _build_history_context(self, previous_rounds: list[OptimizationRound]) -> str:
        """Build context from previous optimization rounds."""
        if not previous_rounds:
            return ""

        context = "\nPrevious rounds (latest first):\n"
        for round_data in reversed(previous_rounds[-3:]):
            context += f"\nRound {round_data.round_number}:\n"
            context += f"Best score this round: {round_data.best_score:.4f}\n"
            context += "Generated prompts this round (best first):\n"

            sorted_generated = sorted(
                round_data.generated_prompts,
                key=lambda p: p.get("score", -float("inf")),
                reverse=True,
            )

            for p in sorted_generated[:3]:
                prompt_text = p.get("prompt", "N/A")
                score = p.get("score", float("nan"))
                context += f"- Prompt: {prompt_text[:150]}...\n"
                context += f"  Avg Score: {score:.4f}\n"
        return context

    def _get_evaluation_subset(
        self, dataset: opik.Dataset, min_size: int = 20, max_size: int = 100
    ) -> list[dict[str, Any]]:
        """Get a random subset of the dataset for evaluation.

        Returns:
            List[Dict[str, Any]]: A list of dataset items to evaluate against
        """
        try:
            # Get all items from the dataset
            all_items = dataset.get_items()
            if not all_items:
                return all_items

            # Calculate subset size
            total_size = len(all_items)
            subset_size = min(max(min_size, int(total_size * 0.2)), max_size)

            # Get random subset of items
            import random

            return random.sample(all_items, subset_size)

        except Exception as e:
            logger.warning(f"Could not create evaluation subset: {e}")
            return all_items
