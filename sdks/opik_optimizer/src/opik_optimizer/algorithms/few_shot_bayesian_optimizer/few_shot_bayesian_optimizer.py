from typing import Any
from collections.abc import Callable

import copy
import hashlib
import json
import logging
import random
from datetime import datetime

import optuna
import optuna.samplers
import optuna.pruners

from opik import Dataset, opik_context

from ... import base_optimizer, _llm_calls, helpers
from ...api_objects import chat_prompt
from ...api_objects.types import MetricFunction
from ...agents import LiteLLMAgent, OptimizableAgent
from ... import _throttle, optimization_result, task_evaluator
from . import reporting, types
from . import prompts as few_shot_prompts
from .columnar_search_space import ColumnarSearchSpace

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
    """

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
    ) -> None:
        super().__init__(
            model, verbose, seed=seed, model_parameters=model_parameters, name=name
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

    # FIXME: Use a centralized RNG function with seed and sampler across all optimizers
    def _make_rng(self, *parts: object) -> random.Random:
        """Create a deterministic RNG keyed by the base seed plus contextual parts (e.g., trial id)."""
        namespace = "|".join(str(part) for part in (self.seed, *parts))
        digest = hashlib.sha256(namespace.encode("utf-8")).digest()
        derived_seed = int.from_bytes(digest[:8], "big")
        return random.Random(derived_seed)

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

        rng = self._make_rng("split_dataset", train_ratio)
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

        messages: list[dict[str, str]] = [
            {"role": "system", "content": few_shot_prompts.SYSTEM_PROMPT_TEMPLATE},
            {"role": "user", "content": json.dumps(user_message)},
        ]

        # Create dynamic response model with explicit fields for each prompt
        DynamicFewShotPromptMessages = types.create_few_shot_response_model(
            prompt_names=list(prompts.keys())
        )

        logger.debug(f"fewshot_prompt_template - Calling LLM with: {messages}")
        response_content = _llm_calls.call_model(
            messages=messages,
            model=model,
            seed=self.seed,
            model_parameters=self.model_parameters,
            response_model=DynamicFewShotPromptMessages,
        )
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

    def _stringify_column_value(self, value: Any) -> str | None:
        """Convert a dataset value to a string suitable for column grouping."""
        if value is None:
            return self._MISSING_VALUE_SENTINEL
        if isinstance(value, (list, dict)):
            return None
        text = str(value)
        if len(text) > self._MAX_COLUMN_VALUE_LENGTH:
            return None
        return text

    def _build_columnar_search_space(
        self, dataset_items: list[dict[str, Any]]
    ) -> ColumnarSearchSpace:
        """
        Infer a lightweight columnar index so Optuna can learn over categorical fields.

        We only keep columns that repeat across rows (avoid high-cardinality text) and
        cap unique values to keep the search space manageable.
        """
        if not dataset_items:
            return ColumnarSearchSpace.empty()

        candidate_columns: list[str] = []
        for key in dataset_items[0]:
            if key == "id":
                continue

            unique_values: set[str] = set()
            skip_column = False
            for item in dataset_items:
                if key not in item:
                    skip_column = True
                    break
                str_value = self._stringify_column_value(item.get(key))
                if str_value is None:
                    skip_column = True
                    break
                unique_values.add(str_value)
                if len(unique_values) > self._MAX_UNIQUE_COLUMN_VALUES:
                    skip_column = True
                    break

            if skip_column:
                continue

            if len(unique_values) < 2 or len(unique_values) >= len(dataset_items):
                continue

            candidate_columns.append(key)

        if not candidate_columns:
            return ColumnarSearchSpace.empty()

        combo_to_indices: dict[str, list[int]] = {}
        for idx, item in enumerate(dataset_items):
            combo_parts: list[str] = []
            skip_example = False
            for column in candidate_columns:
                str_value = self._stringify_column_value(item.get(column))
                if str_value is None:
                    skip_example = True
                    break
                combo_parts.append(f"{column}={str_value}")

            if skip_example:
                continue

            combo_label = "|".join(combo_parts)
            combo_to_indices.setdefault(combo_label, []).append(idx)

        if not combo_to_indices:
            return ColumnarSearchSpace.empty()

        max_group_size = max(len(indices) for indices in combo_to_indices.values())
        combo_labels = sorted(combo_to_indices.keys())
        return ColumnarSearchSpace(
            columns=candidate_columns,
            combo_labels=combo_labels,
            combo_to_indices=combo_to_indices,
            max_group_size=max_group_size,
        )

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

    def _run_optimization(
        self,
        prompts: dict[str, chat_prompt.ChatPrompt],
        original_prompts: dict[str, chat_prompt.ChatPrompt],
        fewshot_prompt_template: str,
        agent: OptimizableAgent,
        dataset: Dataset,
        validation_dataset: Dataset | None,
        metric: MetricFunction,
        baseline_score: float,
        n_trials: int = 10,
        optimization_id: str | None = None,
        experiment_config: dict | None = None,
        n_samples: int | None = None,
        is_single_prompt_optimization: bool = False,
    ) -> optimization_result.OptimizationResult:
        reporting.start_optimization_run(verbose=self.verbose)

        # Load the dataset
        evaluation_dataset = (
            validation_dataset if validation_dataset is not None else dataset
        )

        dataset_items = dataset.get_items()
        columnar_search_space = (
            self._build_columnar_search_space(dataset_items)
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
            rng = self._make_rng("optimization_eval_ids", n_samples)
            eval_dataset_item_ids = rng.sample(eval_dataset_item_ids, n_samples)

        configuration_updates = helpers.drop_none(
            {
                "n_trials": n_trials,
                "n_samples": n_samples,
                "baseline_score": baseline_score,
            }
        )
        base_experiment_config = self._prepare_experiment_config(
            prompt=prompts,
            dataset=dataset,
            validation_dataset=validation_dataset,
            metric=metric,
            experiment_config=experiment_config,
            configuration_updates=configuration_updates,
            is_single_prompt_optimization=is_single_prompt_optimization,
        )

        # Start Optuna Study
        def optimization_objective(trial: optuna.Trial) -> float:
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

            # Display trial start
            reporting.display_trial_start(
                trial_number=trial.number,
                total_trials=n_trials,
                messages=messages_for_reporting,
                verbose=self.verbose,
            )

            score = task_evaluator.evaluate(
                dataset=evaluation_dataset,  # use right dataset for scoring
                dataset_item_ids=eval_dataset_item_ids,
                metric=metric,
                evaluated_task=llm_task,
                num_threads=self.n_threads,
                project_name=self.project_name,
                experiment_config=trial_config,
                optimization_id=optimization_id,
                verbose=self.verbose,
            )

            # Display trial score
            reporting.display_trial_score(
                trial_number=trial.number,
                baseline_score=baseline_score,
                score=score,
                verbose=self.verbose,
            )
            logger.debug(f"Trial {trial.number} score: {score:.4f}")

            # Trial results
            trial_config = {
                "demo_examples": demo_examples,
                "message_list": messages_for_reporting,
            }
            if columnar_choices:
                trial_config["columnar_choices"] = columnar_choices
            trial.set_user_attr("score", score)
            trial.set_user_attr("config", trial_config)
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

        # Explicitly create and seed the sampler for Optuna
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

        optuna_history_processed = []
        for trial_idx, trial in enumerate(study.trials):
            if trial.state == optuna.trial.TrialState.COMPLETE:
                trial_config = trial.user_attrs.get("config", {})
                prompt_cand_display = trial_config.get(
                    "message_list"
                )  # Default to None

                score_val = (
                    trial.value
                )  # This can be None if trial failed to produce a score
                duration_val = None
                if trial.datetime_complete and trial.datetime_start:
                    duration_val = (
                        trial.datetime_complete - trial.datetime_start
                    ).total_seconds()

                iter_detail = {
                    "iteration": trial.number + 1,
                    "timestamp": (
                        trial.datetime_start.isoformat()
                        if trial.datetime_start
                        else datetime.now().isoformat()
                    ),
                    "prompt_candidate": prompt_cand_display,
                    "parameters_used": {
                        "optuna_params": trial.user_attrs.get("config", {}),
                        "example_indices": trial.user_attrs.get(
                            "example_indices", []
                        ),  # Default to empty list
                    },
                    "scores": [
                        {
                            "metric_name": metric.__name__,
                            "score": score_val,  # Can be None
                        }
                    ],
                    "duration_seconds": duration_val,
                }
                optuna_history_processed.append(iter_detail)
            else:
                logger.warning(
                    f"Skipping trial {trial.number} from history due to state: {trial.state}. Value: {trial.value}"
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

        reporting.display_result(
            initial_score=baseline_score,
            best_score=best_score,
            prompt=best_prompts,
            verbose=self.verbose,
        )

        # Handle single vs. dict of prompts for result
        result_best_prompts: chat_prompt.ChatPrompt | dict[str, chat_prompt.ChatPrompt]
        result_initial_prompts: (
            chat_prompt.ChatPrompt | dict[str, chat_prompt.ChatPrompt]
        )
        if is_single_prompt_optimization:
            result_best_prompts = list(best_prompts.values())[0]
            result_initial_prompts = list(original_prompts.values())[0]
        else:
            result_best_prompts = best_prompts
            result_initial_prompts = original_prompts

        return optimization_result.OptimizationResult(
            optimizer=self.__class__.__name__,
            prompt=result_best_prompts,
            initial_prompt=result_initial_prompts,
            initial_score=baseline_score,
            score=best_score,
            metric_name=metric.__name__,
            details={
                "initial_score": baseline_score,
                "prompt_parameter": best_trial.user_attrs.get("config", {}),
                "example_indices": best_example_indices,
                "trial_number": best_trial.number,
                "total_trials": n_trials,
                "total_rounds": n_trials,
                "rounds": [],
                "stopped_early": False,
                "model": self.model,
                "temperature": self.model_parameters.get("temperature"),
            },
            history=optuna_history_processed,
            llm_calls=self.llm_call_counter,
            tool_calls=self.tool_call_counter,
            dataset_id=dataset.id,
            optimization_id=optimization_id,
        )

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
    ) -> optimization_result.OptimizationResult:
        """
        Args:
            prompt: The prompt to optimize
            dataset: Opik Dataset to optimize on
            metric: Metric function to evaluate on
            experiment_config: Optional configuration for the experiment, useful to log additional metadata
            n_samples: Optional number of items to test in the dataset
            auto_continue: Whether to auto-continue optimization
            agent_class: Optional agent class to use
            project_name: Opik project name for logging traces (default: "Optimization")
            max_trials: Number of trials for Bayesian Optimization (default: 10)
            optimization_id: Optional ID for the Opik optimization run; when provided it
                must be a valid UUIDv7 string.
            validation_dataset: Optional validation dataset (not yet supported by this optimizer).

        Returns:
            OptimizationResult: Result of the optimization
        """
        # Use base class validation and setup methods
        if agent is None:
            agent = LiteLLMAgent(project_name=project_name)

        optimizable_prompts: dict[str, chat_prompt.ChatPrompt]
        if isinstance(prompt, chat_prompt.ChatPrompt):
            optimizable_prompts = {prompt.name: prompt}
            is_single_prompt_optimization = True
        else:
            optimizable_prompts = prompt
            is_single_prompt_optimization = False

        self._validate_optimization_inputs(
            optimizable_prompts, dataset, metric, support_content_parts=True
        )

        optimization = None
        try:
            optimization = self.opik_client.create_optimization(
                dataset_name=dataset.name,
                objective_name=metric.__name__,
                metadata=self._build_optimization_metadata(),
                name=self.name,
                optimization_id=optimization_id,
            )
            self.current_optimization_id = optimization.id
        except Exception:
            logger.warning(
                "Opik server does not support optimizations. Please upgrade opik."
            )
            optimization = None
            self.current_optimization_id = None

        # Start experiment reporting
        reporting.display_header(
            algorithm=self.__class__.__name__,
            optimization_id=self.current_optimization_id,
            dataset_id=dataset.id,
            verbose=self.verbose,
        )
        reporting.display_configuration(
            messages=optimizable_prompts,
            optimizer_config={
                "optimizer": self.__class__.__name__,
                "metric": metric.__name__,
                "max_trials": max_trials,
                "n_samples": n_samples,
            },
            verbose=self.verbose,
        )

        evaluation_dataset = (
            validation_dataset if validation_dataset is not None else dataset
        )

        # Step 1. Compute the baseline evaluation
        with reporting.display_evaluation(
            message="First we will establish the baseline performance:",
            verbose=self.verbose,
        ) as eval_report:
            baseline_score = self.evaluate_prompt(
                prompt=optimizable_prompts,
                dataset=evaluation_dataset,
                metric=metric,
                agent=agent,
                n_samples=n_samples,
                experiment_config=self._prepare_experiment_config(
                    prompt=optimizable_prompts,
                    dataset=evaluation_dataset,
                    metric=metric,
                    agent=agent,
                    experiment_config=experiment_config,
                    is_single_prompt_optimization=is_single_prompt_optimization,
                ),
            )

            eval_report.set_score(baseline_score)

        # Step 2. Create the few-shot prompt template
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

        reporting.display_few_shot_prompt_template(
            prompts_with_placeholder=prompts_with_placeholder,
            fewshot_template=fewshot_template,
            verbose=self.verbose,
        )

        # Step 3. Start the optimization process
        result = self._run_optimization(
            prompts=prompts_with_placeholder,
            original_prompts=optimizable_prompts,
            fewshot_prompt_template=fewshot_template,
            agent=agent,
            dataset=dataset,
            validation_dataset=validation_dataset,
            metric=metric,
            baseline_score=baseline_score,
            optimization_id=optimization.id if optimization is not None else None,
            experiment_config=experiment_config,
            n_trials=max_trials,
            n_samples=n_samples,
            is_single_prompt_optimization=is_single_prompt_optimization,
        )
        if optimization:
            self._update_optimization(optimization, status="completed")

        return result

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
            prompts_with_examples = {}

            for key, prompt in prompts.items():
                new_prompt = prompt.copy()
                new_messages = new_prompt.replace_in_messages(
                    new_prompt.get_messages(),
                    few_shot_prompts.FEW_SHOT_EXAMPLE_PLACEHOLDER,
                    few_shot_examples,
                )
                new_prompt.set_messages(new_messages)
                prompts_with_examples[key] = new_prompt

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
        demo_examples: list[dict[str, Any]],
        fewshot_prompt_template: str,
    ) -> dict[str, chat_prompt.ChatPrompt]:
        """
        Reconstruct the prompts dict with few-shot examples filled in.

        Args:
            prompts_with_placeholder: The template prompts with FEW_SHOT_EXAMPLE_PLACEHOLDER
            demo_examples: List of example dictionaries from the dataset
            fewshot_prompt_template: The template string for formatting each example

        Returns:
            Dictionary of ChatPrompt objects with examples filled in
        """
        # Build the few_shot_examples string from demo_examples
        few_shot_examples, _ = self._build_few_shot_examples_string(
            demo_examples, fewshot_prompt_template
        )

        # Fill in the placeholder in each prompt
        result_prompts = {}
        for key, prompt in prompts_with_placeholder.items():
            new_prompt = prompt.copy()
            new_messages = new_prompt.replace_in_messages(
                new_prompt.get_messages(),
                few_shot_prompts.FEW_SHOT_EXAMPLE_PLACEHOLDER,
                few_shot_examples,
            )
            new_prompt.set_messages(new_messages)
            result_prompts[key] = new_prompt

        return result_prompts
