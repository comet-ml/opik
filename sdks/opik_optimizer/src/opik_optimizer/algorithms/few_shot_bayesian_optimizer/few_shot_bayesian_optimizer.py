from typing import Any, cast

import copy
import json
import logging
import warnings
from datetime import datetime

import optuna
import optuna.samplers
import optuna.pruners

from opik import Dataset, opik_context

from ... import base_optimizer
from ...core import llm_calls as _llm_calls
from ...core import runtime
from ...core.state import (
    OptimizationContext,
    AlgorithmResult,
    prepare_experiment_config,
)
from ...api_objects import chat_prompt
from ...api_objects.types import MetricFunction
from ...agents import OptimizableAgent
from ...core import evaluation as task_evaluator
from ... import helpers as parent_helpers
from ...utils import throttle as _throttle
from ...utils.prompt_library import PromptOverrides
from ...utils.logging import debug_log
from . import helpers, types
from . import prompts as few_shot_prompts
from .ops.columnarsearch_ops import ColumnarSearchSpace, build_columnar_search_space
from collections.abc import Callable

_limiter = _throttle.get_rate_limiter_for_current_opik_installation()

logger = logging.getLogger(__name__)


class FewShotBayesianOptimizer(base_optimizer.BaseOptimizer):
    """
    Few-Shot Bayesian Optimizer that adds few-shot examples to prompts using Bayesian optimization.

    This algorithm employs a two-stage pipeline:

    1. Generate a few-shot prompt template that can be inserted into the prompt
    2. Use Bayesian Optimization to determine the best examples to include in the prompt

    This algorithm is best used when you have a well-defined task and would like to guide the LLM
    by providing examples. It automatically finds the optimal number and selection of examples.

    Args:
        model: LiteLLM model name for optimizer's internal reasoning (generating few-shot templates)
        model_parameters: Optional dict of LiteLLM parameters for optimizer's internal LLM calls.
            Common params: temperature, max_tokens, max_completion_tokens, top_p.
            See: https://docs.litellm.ai/docs/completion/input
        min_examples: Minimum number of examples to include in the prompt
        max_examples: Maximum number of examples to include in the prompt
        n_threads: Number of threads for parallel evaluation
        verbose: Controls internal logging/progress bars (0=off, 1=on)
        seed: Random seed for reproducibility
        enable_columnar_selection: Toggle column-aware example grouping (categorical Optuna params)
        enable_multivariate_tpe: Enable Optuna's multivariate TPE sampler (default: True)
        enable_optuna_pruning: Enable Optuna pruner for early stopping (default: True)
        prompt_overrides: Optional dict or callable to override/customize prompt templates.
            If a dict, keys should match DEFAULT_PROMPTS keys.
            If a callable, receives the PromptLibrary instance for in-place modification.
    """

    DEFAULT_PROMPTS: dict[str, str] = {
        "example_placeholder": few_shot_prompts.FEW_SHOT_EXAMPLE_PLACEHOLDER,
        "system_prompt_template": few_shot_prompts.SYSTEM_PROMPT_TEMPLATE,
    }

    _MAX_UNIQUE_COLUMN_VALUES = 25
    _MAX_COLUMN_VALUE_LENGTH = 120
    _MISSING_VALUE_SENTINEL = "<missing>"

    def __init__(
        self,
        model: str = "gpt-4o",
        model_parameters: dict[str, Any] | None = None,
        min_examples: int = 2,
        max_examples: int = 8,
        n_threads: int = 8,
        verbose: int = 1,
        seed: int = 42,
        name: str | None = None,
        enable_columnar_selection: bool = True,
        enable_diversity: bool = True,
        enable_multivariate_tpe: bool = True,
        enable_optuna_pruning: bool = True,
        prompt_overrides: PromptOverrides = None,
        skip_perfect_score: bool = True,
        perfect_score: float = 0.95,
    ) -> None:
        super().__init__(
            model,
            verbose,
            seed=seed,
            model_parameters=model_parameters,
            name=name,
            skip_perfect_score=skip_perfect_score,
            perfect_score=perfect_score,
            prompt_overrides=prompt_overrides,
        )
        self.min_examples = min_examples
        self.max_examples = max_examples
        self.seed = seed
        self.n_threads = n_threads
        self.enable_columnar_selection = enable_columnar_selection
        self.enable_diversity = enable_diversity
        self.enable_multivariate_tpe = enable_multivariate_tpe
        self.enable_optuna_pruning = enable_optuna_pruning
        if self.verbose == 0:
            logger.setLevel(logging.WARNING)
        elif self.verbose == 1:
            logger.setLevel(logging.INFO)
        elif self.verbose == 2:
            logger.setLevel(logging.DEBUG)

        # Instance state for custom evaluation (used by overridden evaluate_prompt)
        self._custom_evaluated_task: Callable[..., dict[str, Any]] | None = None
        self._custom_eval_item_ids: list[str] | None = None

        logger.debug(f"Initialized FewShotBayesianOptimizer with model: {model}")

    def get_optimizer_metadata(self) -> dict[str, Any]:
        return {
            "min_examples": self.min_examples,
            "max_examples": self.max_examples,
            "enable_columnar_selection": self.enable_columnar_selection,
            "enable_diversity": self.enable_diversity,
            "enable_multivariate_tpe": self.enable_multivariate_tpe,
            "enable_optuna_pruning": self.enable_optuna_pruning,
        }

    def evaluate_prompt(  # type: ignore[override]
        self,
        prompt: chat_prompt.ChatPrompt | dict[str, chat_prompt.ChatPrompt],
        dataset: Dataset,
        metric: MetricFunction,
        agent: OptimizableAgent | None = None,
        n_threads: int | None = None,
        verbose: int = 1,
        dataset_item_ids: list[str] | None = None,
        experiment_config: dict | None = None,
        n_samples: int | None = None,
        seed: int | None = None,
        return_evaluation_result: bool = False,
        allow_tool_use: bool | None = None,
    ) -> float:
        """
        Override evaluate_prompt to support custom evaluated_task.

        When _custom_evaluated_task is set (during Bayesian optimization),
        uses the pre-built task with few-shot examples injected instead of
        building one from the prompt parameter.
        """
        if self._custom_evaluated_task is not None:
            # Use the custom task (with few-shot examples already injected)
            if n_threads is None:
                n_threads = self.n_threads

            return task_evaluator.evaluate(
                dataset=dataset,
                evaluated_task=self._custom_evaluated_task,
                metric=metric,
                num_threads=n_threads,
                dataset_item_ids=self._custom_eval_item_ids,
                project_name=self.project_name,
                experiment_config=experiment_config,
                optimization_id=self.current_optimization_id,
                verbose=verbose,
            )

        # Default behavior: delegate to parent
        # Note: This method always returns float, so we pass literal False
        # to help mypy narrow the overload correctly
        return super().evaluate_prompt(
            prompt=prompt,
            dataset=dataset,
            metric=metric,
            agent=agent,
            n_threads=n_threads,
            verbose=verbose,
            dataset_item_ids=dataset_item_ids,
            experiment_config=experiment_config,
            n_samples=n_samples,
            seed=seed,
            return_evaluation_result=False,
            allow_tool_use=allow_tool_use,
        )

    def get_config(self, context: OptimizationContext) -> dict[str, Any]:
        """Return optimizer-specific configuration for display."""
        return {
            "optimizer": self.__class__.__name__,
            "metric": context.metric.__name__,
            "max_trials": context.max_trials,
            "n_samples": context.n_samples,
        }

    def get_metadata(self, context: OptimizationContext) -> dict[str, Any]:
        """
        Return FewShotBayesian-specific metadata for the optimization result.

        Provides trials and rounds tracking that can be used in any scenario
        (early stop, completion, etc.). The optimizer doesn't know why this
        is being called - it just provides its current state.
        """
        return {
            "total_trials": context.max_trials,
            "total_rounds": context.max_trials,
            "rounds": [],
            "trials_completed": context.trials_completed,
        }

    # FIXME: Dead code, should be wired or removed
    def _split_dataset(
        self, dataset: list[dict[str, Any]], train_ratio: float
    ) -> tuple[list[dict[str, Any]], list[dict[str, Any]]]:
        """
        Split the dataset into training and validation sets.

        Args:
            dataset: List of dataset items
            train_ratio: Ratio of items to use for training

        Returns:
            Tuple of (train_set, validation_set)
        """
        if not dataset:
            return [], []

        rng = helpers.make_rng(self.seed, "split_dataset", train_ratio)
        dataset_copy = dataset.copy()
        rng.shuffle(dataset_copy)

        split_idx = int(len(dataset_copy) * train_ratio)
        return dataset_copy[:split_idx], dataset_copy[split_idx:]

    def _create_fewshot_prompt_template(
        self,
        model: str,
        prompts: dict[str, chat_prompt.ChatPrompt],
        few_shot_examples: list[dict[str, Any]],
    ) -> tuple[dict[str, chat_prompt.ChatPrompt], str]:
        """
        Generate a few-shot prompt template that can be used to insert examples into the prompt.

        Args:
            model: The model to use for generating the template
            prompt: The base prompts to modify
            few_shot_examples: List of example pairs with input and output fields

        Returns:
            A tuple containing the updated prompts and the example template
        """
        # During this step we update the system prompt to include few-shot examples.
        user_message = {
            "prompts": [
                {"name": name, "messages": value.get_messages()}
                for name, value in prompts.items()
            ],
            "examples": few_shot_examples,
        }

        # Get templates from prompt library (allows for overrides)
        system_template = self.get_prompt("system_prompt_template")
        example_placeholder = str(self.get_prompt("example_placeholder"))

        messages: list[dict[str, str]] = [
            {
                "role": "system",
                "content": few_shot_prompts.build_system_prompt_template(
                    template=system_template,
                    placeholder=example_placeholder,
                ),
            },
            {"role": "user", "content": json.dumps(user_message)},
        ]

        # Create dynamic response model with explicit fields for each prompt
        DynamicFewShotPromptMessages = types.create_few_shot_response_model(
            prompt_names=list(prompts.keys())
        )

        logger.debug(f"fewshot_prompt_template - Calling LLM with: {messages}")
        # Few-shot prompt synthesis expects a single structured response.
        model_parameters = dict(self.model_parameters)
        model_parameters.pop("n", None)
        response_content = _llm_calls.call_model(
            messages=messages,
            model=model,
            seed=self.seed,
            model_parameters=model_parameters,
            response_model=DynamicFewShotPromptMessages,
        )
        if isinstance(response_content, list):
            response_content = response_content[0]
        logger.debug(f"fewshot_prompt_template - LLM response: {response_content}")

        new_prompts: dict[str, chat_prompt.ChatPrompt] = {}
        for prompt_name in prompts.keys():
            try:
                # Access field using getattr since field names are dynamic
                messages = [
                    x.model_dump(mode="json")
                    for x in getattr(response_content, prompt_name)
                ]
                new_prompt = prompts[prompt_name].copy()
                new_prompt.set_messages(messages)
            except Exception as e:
                logger.error(
                    f"Couldn't create prompt with placeholder for {prompt_name}: {e}"
                )
                raise

            new_prompts[prompt_name] = new_prompt

        return new_prompts, str(response_content.template)

    def _suggest_example_index(
        self,
        trial: optuna.Trial,
        example_position: int,
        dataset_size: int,
        columnar_space: ColumnarSearchSpace,
        selected_indices: set[int],
    ) -> tuple[int, dict[str, Any] | None]:
        """
        Suggest an example index for the given trial, optionally using column-aware combos.
        """
        if not columnar_space.is_enabled:
            index = trial.suggest_categorical(
                f"example_{example_position}", list(range(dataset_size))
            )
            adjusted_index = self._apply_diversity_adjustment(
                resolved_index=index,
                selected_indices=selected_indices,
                dataset_size=dataset_size,
                combo_candidates=None,
            )
            return adjusted_index, None

        combo_label = trial.suggest_categorical(
            f"example_{example_position}_combo", columnar_space.combo_labels
        )
        member_upper_bound = max(columnar_space.max_group_size - 1, 0)
        member_index = trial.suggest_int(
            f"example_{example_position}_member", 0, member_upper_bound
        )
        resolved_index = columnar_space.select_index(combo_label, member_index)
        adjusted_index = self._apply_diversity_adjustment(
            resolved_index=resolved_index,
            selected_indices=selected_indices,
            dataset_size=dataset_size,
            combo_candidates=columnar_space.combo_to_indices.get(combo_label, []),
            start_offset=member_index,
        )
        diversity_adjusted = adjusted_index != resolved_index
        return adjusted_index, {
            "combo": combo_label,
            "member_index": member_index,
            "resolved_index": adjusted_index,
            "diversity_adjusted": diversity_adjusted,
        }

    def _apply_diversity_adjustment(
        self,
        *,
        resolved_index: int,
        selected_indices: set[int],
        dataset_size: int,
        combo_candidates: list[int] | None = None,
        start_offset: int = 0,
    ) -> int:
        """
        Encourage within-trial diversity by steering away from already selected indices.
        """
        if not self.enable_diversity or resolved_index not in selected_indices:
            return resolved_index

        if combo_candidates:
            for offset in range(len(combo_candidates)):
                candidate = combo_candidates[
                    (start_offset + offset) % len(combo_candidates)
                ]
                if candidate not in selected_indices:
                    return candidate

        for offset in range(dataset_size):
            candidate = (resolved_index + offset) % dataset_size
            if candidate not in selected_indices:
                return candidate

        return resolved_index

    def _run_bayesian_optimization(
        self,
        context: OptimizationContext,
        prompts: dict[str, chat_prompt.ChatPrompt],
        original_prompts: dict[str, chat_prompt.ChatPrompt],
        fewshot_prompt_template: str,
        agent: OptimizableAgent | None,
        dataset: Dataset,
        validation_dataset: Dataset | None,
        metric: MetricFunction,
        baseline_score: float,
        n_trials: int = 10,
        optimization_id: str | None = None,
        experiment_config: dict | None = None,
        n_samples: int | None = None,
        is_single_prompt_optimization: bool = False,
    ) -> AlgorithmResult:
        if agent is None:
            raise ValueError(
                "FewShotBayesianOptimizer requires an agent for optimization."
            )
        # Load the dataset
        evaluation_dataset = (
            validation_dataset if validation_dataset is not None else dataset
        )

        dataset_items = dataset.get_items()
        columnar_search_space = (
            build_columnar_search_space(
                dataset_items, max_unique_column_values=self._MAX_UNIQUE_COLUMN_VALUES
            )
            if self.enable_columnar_selection
            else ColumnarSearchSpace.empty()
        )
        if columnar_search_space.is_enabled:
            logger.debug(
                "Column-aware search enabled with columns: %s",
                columnar_search_space.columns,
            )

        eval_dataset_items = evaluation_dataset.get_items()
        eval_dataset_item_ids = [item["id"] for item in eval_dataset_items]
        if n_samples is not None and n_samples < len(dataset_items):
            rng = helpers.make_rng(self.seed, "optimization_eval_ids", n_samples)
            eval_dataset_item_ids = rng.sample(eval_dataset_item_ids, n_samples)

        configuration_updates = parent_helpers.drop_none(
            {
                "n_trials": n_trials,
                "n_samples": n_samples,
                "baseline_score": baseline_score,
            }
        )
        base_experiment_config = prepare_experiment_config(
            optimizer=self,
            prompt=prompts,
            dataset=dataset,
            validation_dataset=validation_dataset,
            metric=metric,
            experiment_config=experiment_config,
            configuration_updates=configuration_updates,
            additional_metadata=None,
            is_single_prompt_optimization=is_single_prompt_optimization,
            agent=agent,
            build_optimizer_version=base_optimizer._OPTIMIZER_VERSION,
        )

        # Start Optuna Study
        def optimization_objective(trial: optuna.Trial) -> float:
            # Check should_stop flag at start of each trial
            if context.should_stop:
                raise optuna.exceptions.TrialPruned()

            n_examples = trial.suggest_int(
                "n_examples", self.min_examples, self.max_examples
            )
            example_indices: list[int] = []
            columnar_choices: list[dict[str, Any]] = []
            selected_indices: set[int] = set()
            for i in range(n_examples):
                selected_index, column_choice = self._suggest_example_index(
                    trial=trial,
                    example_position=i,
                    dataset_size=len(dataset_items),
                    columnar_space=columnar_search_space,
                    selected_indices=selected_indices,
                )
                example_indices.append(selected_index)
                selected_indices.add(selected_index)
                if column_choice:
                    columnar_choices.append(column_choice)
            trial.set_user_attr("example_indices", example_indices)
            if columnar_choices:
                trial.set_user_attr("columnar_choices", columnar_choices)

            # Process few shot examples
            demo_examples = [dataset_items[idx] for idx in example_indices]

            # Build few-shot examples string and processed examples for reporting
            few_shot_examples, processed_demo_examples = (
                self._build_few_shot_examples_string(
                    demo_examples=demo_examples,
                    fewshot_prompt_template=fewshot_prompt_template,
                )
            )

            # Use the helper to build prompts with examples
            prompts_with_examples = self._reconstruct_prompts_with_examples(
                prompts_with_placeholder=prompts,
                demo_examples=demo_examples,
                fewshot_prompt_template=fewshot_prompt_template,
            )

            llm_task = self._build_task_from_messages(
                agent=agent,
                prompts=prompts,
                few_shot_examples=few_shot_examples,
            )

            # Build messages for reporting from the prompts with examples
            messages_for_reporting = []
            for prompt_obj in prompts_with_examples.values():
                messages_for_reporting.extend(prompt_obj.get_messages())

            # Log trial config
            trial_config = copy.deepcopy(base_experiment_config)
            trial_config["configuration"]["prompt"] = (
                messages_for_reporting  # Base instruction
            )
            trial_config["configuration"]["examples"] = (
                processed_demo_examples  # Log stringified examples
            )
            trial_config["configuration"]["n_examples"] = n_examples
            trial_config["configuration"]["example_indices"] = example_indices

            logger.debug(
                f"Trial {trial.number}: n_examples={n_examples}, indices={example_indices}"
            )
            logger.debug(f"Evaluating trial {trial.number}...")
            debug_log(
                "trial_start",
                trial_index=trial.number + 1,
                trials_completed=context.trials_completed,
                max_trials=n_trials,
            )

            # Set custom task for evaluate_prompt override
            self._custom_evaluated_task = llm_task
            self._custom_eval_item_ids = eval_dataset_item_ids
            try:
                # Use base optimizer's evaluate() which handles:
                # - Trial counting (context.trials_completed)
                # - Early stop checks (perfect score, max trials)
                # - Progress display hooks
                score = self.evaluate(context, prompts_with_examples, trial_config)
            finally:
                # Clear custom task state
                self._custom_evaluated_task = None
                self._custom_eval_item_ids = None

            logger.debug(f"Trial {trial.number} score: {score:.4f}")
            debug_log(
                "trial_end",
                trial_index=trial.number + 1,
                score=score,
                trials_completed=context.trials_completed,
            )

            # Trial results for Optuna
            trial_result_config = {
                "demo_examples": demo_examples,
                "message_list": messages_for_reporting,
            }
            if columnar_choices:
                trial_result_config["columnar_choices"] = columnar_choices
            trial.set_user_attr("score", score)
            trial.set_user_attr("config", trial_result_config)

            return score

        # Configure Optuna Logging
        try:
            optuna.logging.disable_default_handler()
            optuna_logger = logging.getLogger("optuna")
            package_level = logging.getLogger("opik_optimizer").getEffectiveLevel()
            optuna_logger.setLevel(package_level)
            optuna_logger.propagate = False
            logger.debug(
                f"Optuna logger configured to level {logging.getLevelName(package_level)} and set to not propagate."
            )
        except Exception as e:
            logger.warning(f"Could not configure Optuna logging within optimizer: {e}")

        # Explicitly create and seed the sampler for Optuna.
        # Note: Optuna warns that `multivariate` is experimental; we suppress that warning here.
        with warnings.catch_warnings():
            warnings.filterwarnings(
                "ignore",
                message=r".*multivariate.*experimental.*",
                category=optuna.exceptions.ExperimentalWarning,
            )
            sampler = optuna.samplers.TPESampler(
                seed=self.seed, multivariate=self.enable_multivariate_tpe
            )
        pruner = (
            optuna.pruners.MedianPruner(n_startup_trials=3)
            if self.enable_optuna_pruning
            else optuna.pruners.NopPruner()
        )
        study = optuna.create_study(
            direction="maximize", sampler=sampler, pruner=pruner
        )

        study.optimize(
            optimization_objective, n_trials=n_trials, show_progress_bar=False
        )

        self._history_builder.clear()
        for trial in study.trials:
            if trial.state != optuna.trial.TrialState.COMPLETE:
                logger.warning(
                    f"Skipping trial {trial.number} from history due to state: {trial.state}. Value: {trial.value}"
                )
                continue

            trial_config = trial.user_attrs.get("config", {})
            prompt_cand_display = trial_config.get("message_list")  # Default to None
            score_val = (
                trial.value
            )  # This can be None if trial failed to produce a score
            timestamp = (
                trial.datetime_start.isoformat()
                if trial.datetime_start
                else datetime.now().isoformat()
            )
            sampler_info = type(study.sampler).__name__ if study.sampler else None
            pruner_info = type(study.pruner).__name__ if study.pruner else None
            round_handle = self.pre_round(
                context,
                sampler=sampler_info,
                pruner=pruner_info,
                study_direction=study.direction.name if study.direction else None,
            )
            runtime.record_and_post_trial(
                optimizer=self,
                context=context,
                prompt_or_payload=prompt_cand_display,
                score=score_val,
                candidate_id=f"trial{trial.number}",
                extra={
                    "parameters": trial.user_attrs.get("parameters", {}),
                    "model_kwargs": trial.user_attrs.get("model_kwargs", {}),
                    "model": trial.user_attrs.get("model", {}),
                    "stage": trial.user_attrs.get("stage"),
                    "type": trial.user_attrs.get("type"),
                },
                round_handle=round_handle,
                timestamp=timestamp,
                post_extras=None,
                post_metrics=None,
            )
            self.set_selection_meta(
                {
                    "sampler": sampler_info,
                    "pruner": pruner_info,
                    "study_direction": study.direction.name
                    if study.direction
                    else None,
                    "stage": trial.user_attrs.get("stage")
                    if trial is not None
                    else None,
                }
            )
            self.post_round(
                round_handle,
                context=context,
                stop_reason=context.finish_reason,
            )

        best_trial = study.best_trial
        best_score = best_trial.value
        best_example_indices = best_trial.user_attrs.get("example_indices", [])
        best_demo_examples = best_trial.user_attrs.get("config", {}).get(
            "demo_examples", []
        )

        if best_score <= baseline_score:
            # Optimization didn't improve, return original prompts without placeholder
            best_score = baseline_score
            best_prompts = original_prompts
        else:
            # Reconstruct the best prompts from the demo examples
            best_prompts = self._reconstruct_prompts_with_examples(
                prompts_with_placeholder=prompts,
                demo_examples=best_demo_examples,
                fewshot_prompt_template=fewshot_prompt_template,
            )

        # finish_reason, stopped_early, stop_reason are handled by base class

        return AlgorithmResult(
            best_prompts=best_prompts,
            best_score=best_score,
            history=self.get_history_entries(),
            metadata={
                # Algorithm-specific fields only (framework fields handled by base)
                "prompt_parameter": best_trial.user_attrs.get("config", {}),
                "example_indices": best_example_indices,
                "trial_number": best_trial.number,
            },
        )

    def run_optimization(
        self,
        context: OptimizationContext,
    ) -> AlgorithmResult:
        optimizable_prompts = context.prompts
        self.set_default_dataset_split(
            "validation" if context.validation_dataset is not None else "train"
        )
        dataset = context.dataset
        validation_dataset = context.validation_dataset
        metric = context.metric
        agent = context.agent
        experiment_config = context.experiment_config
        max_trials = context.max_trials
        optimization_id = context.optimization_id
        baseline_score = cast(float, context.baseline_score)
        n_samples = context.n_samples
        is_single_prompt_optimization = context.is_single_prompt_optimization

        prompts_with_placeholder, fewshot_template = (
            self._create_fewshot_prompt_template(
                model=self.model,
                prompts=optimizable_prompts,
                few_shot_examples=[
                    {k: v for k, v in item.items() if k != "id"}
                    for item in dataset.get_items(nb_samples=10)
                ],
            )
        )

        logger.debug(
            "Generated few-shot prompt template (placeholder=%s)",
            self.get_prompt("example_placeholder"),
        )

        return self._run_bayesian_optimization(
            context=context,
            prompts=prompts_with_placeholder,
            original_prompts=optimizable_prompts,
            fewshot_prompt_template=fewshot_template,
            agent=agent,
            dataset=dataset,
            validation_dataset=validation_dataset,
            metric=metric,
            baseline_score=baseline_score,
            optimization_id=optimization_id,
            experiment_config=experiment_config,
            n_trials=max_trials,
            n_samples=n_samples,
            is_single_prompt_optimization=is_single_prompt_optimization,
        )

    def _build_task_from_messages(
        self,
        agent: OptimizableAgent,
        prompts: dict[str, chat_prompt.ChatPrompt],
        few_shot_examples: str,
    ) -> Callable[[dict[str, Any]], dict[str, Any]]:
        def llm_task(dataset_item: dict[str, Any]) -> dict[str, Any]:
            """
            Process a single dataset item through the LLM task.

            Args:
                dataset_item: Dictionary containing the dataset item data

            Returns:
                Dictionary containing the LLM's response
            """
            prompts_with_examples = self._reconstruct_prompts_with_examples(
                prompts_with_placeholder=prompts,
                few_shot_examples=few_shot_examples,
            )

            result = agent.invoke_agent(
                prompts=prompts_with_examples,
                dataset_item=dataset_item,
                seed=self.seed,
            )

            # Add tags to trace for optimization tracking
            if self.current_optimization_id:
                opik_context.update_current_trace(
                    tags=[self.current_optimization_id, "Evaluation"]
                )

            return {"llm_output": result}

        return llm_task

    def _build_few_shot_examples_string(
        self,
        demo_examples: list[dict[str, Any]],
        fewshot_prompt_template: str,
    ) -> tuple[str, list[str]]:
        """
        Build the few-shot examples string from demo examples.

        Args:
            demo_examples: List of example dictionaries from the dataset
            fewshot_prompt_template: The template string for formatting each example

        Returns:
            Tuple of (few_shot_examples_string, list_of_processed_examples)
        """
        processed_demo_examples = []
        for example in demo_examples:
            processed_example = {}
            for key, value in example.items():
                processed_example[key] = str(value)

            processed_demo_example = fewshot_prompt_template
            for key, value in processed_example.items():
                try:
                    processed_demo_example = processed_demo_example.replace(
                        f"{{{key}}}", str(value)
                    )
                except Exception:
                    logger.error(
                        f"Failed to format fewshot prompt template {fewshot_prompt_template} with example: {processed_example} "
                    )
                    raise
            processed_demo_examples.append(processed_demo_example)

        few_shot_examples = "\n\n".join(processed_demo_examples)
        return few_shot_examples, processed_demo_examples

    def _reconstruct_prompts_with_examples(
        self,
        prompts_with_placeholder: dict[str, chat_prompt.ChatPrompt],
        demo_examples: list[dict[str, Any]] | None = None,
        fewshot_prompt_template: str | None = None,
        few_shot_examples: str | None = None,
    ) -> dict[str, chat_prompt.ChatPrompt]:
        """
        Reconstruct the prompts dict with few-shot examples filled in.

        Args:
            prompts_with_placeholder: The template prompts with the few-shot placeholder
            demo_examples: List of example dictionaries from the dataset
            fewshot_prompt_template: The template string for formatting each example
            few_shot_examples: Preformatted few-shot string to insert directly

        Returns:
            Dictionary of ChatPrompt objects with examples filled in
        """
        if few_shot_examples is None:
            if demo_examples is None or fewshot_prompt_template is None:
                raise ValueError(
                    "demo_examples and fewshot_prompt_template are required when few_shot_examples is None."
                )
            few_shot_examples, _ = self._build_few_shot_examples_string(
                demo_examples, fewshot_prompt_template
            )

        # Fill in the placeholder in each prompt
        result_prompts = {}
        example_placeholder = str(self.get_prompt("example_placeholder"))
        for key, prompt in prompts_with_placeholder.items():
            new_prompt = prompt.copy()
            new_messages = new_prompt.replace_in_messages(
                new_prompt.get_messages(),
                example_placeholder,
                few_shot_examples,
            )
            new_prompt.set_messages(new_messages)
            result_prompts[key] = new_prompt

        return result_prompts
