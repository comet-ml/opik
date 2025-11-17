from typing import Any
from collections.abc import Callable

import copy
import json
import logging
import random
from datetime import datetime

import optuna
import optuna.samplers

import opik
from opik import Dataset, opik_context
from pydantic import BaseModel

from ... import base_optimizer, _llm_calls, helpers
from ...api_objects import chat_prompt
from ...optimizable_agent import OptimizableAgent
from ... import _throttle, optimization_result, task_evaluator, utils
from . import reporting

_limiter = _throttle.get_rate_limiter_for_current_opik_installation()

logger = logging.getLogger(__name__)

FEW_SHOT_EXAMPLE_PLACEHOLDER = "FEW_SHOT_EXAMPLE_PLACEHOLDER"
SYSTEM_PROMPT_TEMPLATE = f"""
You are a prompt editor that modifies a message list to support few-shot learning. Your job is to insert a placeholder where few-shot examples can be inserted and generate a reusable string template for formatting those examples.

You will receive a JSON object with the following fields:

- "message_list": a list of messages, each with a role (system, user, or assistant) and a content field.
- "examples": a list of example pairs, each with input and output fields.

Your task:

- Insert the string "{FEW_SHOT_EXAMPLE_PLACEHOLDER}" into one of the messages in the list. Make sure to:
    - Insert it at the most logical point for including few-shot examples — typically as part of the system message
    - Add a section title in XML or markdown format. The examples will be provided as `example_1\nexample_2\n...` with each example following the example template.
- Analyze the examples to infer a consistent structure, and create a single string few_shot_example_template using the Python .format() style. Make sure to follow the following instructions:
    - Unless absolutely relevant, do not return an object but instead a string that can be inserted as part of {FEW_SHOT_EXAMPLE_PLACEHOLDER}
    - Make sure to include the variables as part of this string so we can before string formatting with actual examples. Only variables available in the examples can be used.
    - Do not apply any transformations to the variables either, only the variable name should be included in the format `{{<variable_name>}}`
    - The few shot examples should include the expected response as the goal is to provide examples of the response.
    - Ensure the format of the few shot examples are consistent with how the model will be called

Return your output as a JSON object with:

- message_list_with_placeholder: the updated list with "FEW_SHOT_EXAMPLE_PLACEHOLDER" inserted.
- example_template: a string template using the fields provided in the examples (you don't need to use all of them)

Respond only with the JSON object. Do not include any explanation or extra text.
"""


class FewShotPromptTemplate(BaseModel):
    message_list_with_placeholder: list[dict[str, str]]
    example_template: str


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
    """

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
    ) -> None:
        super().__init__(
            model, verbose, seed=seed, model_parameters=model_parameters, name=name
        )
        self.min_examples = min_examples
        self.max_examples = max_examples
        self.seed = seed
        self.n_threads = n_threads
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
        }

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
        """Split the dataset into training and validation sets.

        Args:
            dataset: List of dataset items
            train_ratio: Ratio of items to use for training

        Returns:
            Tuple of (train_set, validation_set)
        """
        if not dataset:
            return [], []

        random.seed(self.seed)
        dataset = dataset.copy()
        random.shuffle(dataset)

        split_idx = int(len(dataset) * train_ratio)
        return dataset[:split_idx], dataset[split_idx:]

    def _create_fewshot_prompt_template(
        self,
        model: str,
        prompt: chat_prompt.ChatPrompt,
        few_shot_examples: list[dict[str, Any]],
    ) -> FewShotPromptTemplate:
        """
        Generate a few-shot prompt template that can be used to insert examples into the prompt.

        Args:
            model: The model to use for generating the template
            prompt: The base prompt to modify
            few_shot_examples: List of example pairs with input and output fields

        Returns:
            FewShotPromptTemplate containing the modified message list and example template
        """
        """
        During this step we update the system prompt to include few-shot examples.
        """
        user_message = {
            "message_list": prompt.get_messages(),
            "examples": few_shot_examples,
        }

        messages: list[dict[str, str]] = [
            {"role": "system", "content": SYSTEM_PROMPT_TEMPLATE},
            {"role": "user", "content": json.dumps(user_message)},
        ]

        logger.debug(f"fewshot_prompt_template - Calling LLM with: {messages}")
        response_content = _llm_calls.call_model(
            messages=messages,
            model=model,
            seed=self.seed,
            model_parameters=self.model_parameters,
        )
        logger.debug(f"fewshot_prompt_template - LLM response: {response_content}")

        try:
            res = utils.json_to_dict(response_content)
            return FewShotPromptTemplate(
                message_list_with_placeholder=res["message_list_with_placeholder"],
                example_template=res["example_template"],
            )
        except Exception as e:
            logger.error(
                f"Failed to compute few-shot prompt template: {e} - response: {response_content}"
            )
            raise

    def _run_optimization(
        self,
        prompt: chat_prompt.ChatPrompt,
        fewshot_prompt_template: FewShotPromptTemplate,
        dataset: Dataset,
        metric: Callable,
        baseline_score: float,
        n_trials: int = 10,
        optimization_id: str | None = None,
        experiment_config: dict | None = None,
        n_samples: int | None = None,
    ) -> optimization_result.OptimizationResult:
        reporting.start_optimization_run(verbose=self.verbose)

        random.seed(self.seed)

        # Load the dataset
        dataset_items = dataset.get_items()
        all_dataset_item_ids = [item["id"] for item in dataset_items]
        eval_dataset_item_ids = all_dataset_item_ids
        if n_samples is not None and n_samples < len(dataset_items):
            eval_dataset_item_ids = random.sample(all_dataset_item_ids, n_samples)

        configuration_updates = helpers.drop_none(
            {
                "n_trials": n_trials,
                "n_samples": n_samples,
                "baseline_score": baseline_score,
            }
        )
        base_experiment_config = self._prepare_experiment_config(
            prompt=prompt,
            dataset=dataset,
            metric=metric,
            experiment_config=experiment_config,
            configuration_updates=configuration_updates,
        )

        # Start Optuna Study
        def optimization_objective(trial: optuna.Trial) -> float:
            n_examples = trial.suggest_int(
                "n_examples", self.min_examples, self.max_examples
            )
            example_indices = [
                trial.suggest_categorical(
                    f"example_{i}", list(range(len(dataset_items)))
                )
                for i in range(n_examples)
            ]
            trial.set_user_attr("example_indices", example_indices)

            # Process few shot examples
            demo_examples = [dataset_items[idx] for idx in example_indices]

            processed_demo_examples = []
            for example in demo_examples:
                processed_example = {}
                for key, value in example.items():
                    processed_example[key] = str(value)

                processed_demo_example = fewshot_prompt_template.example_template
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

            llm_task = self._build_task_from_messages(
                prompt=prompt,
                messages=fewshot_prompt_template.message_list_with_placeholder,
                few_shot_examples=few_shot_examples,
            )

            messages_for_reporting = [
                {
                    "role": item["role"],
                    "content": item["content"].replace(
                        FEW_SHOT_EXAMPLE_PLACEHOLDER, few_shot_examples
                    ),
                }
                for item in fewshot_prompt_template.message_list_with_placeholder
            ]

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

            with reporting.start_optimization_trial(
                trial.number, n_trials, verbose=self.verbose
            ) as trial_reporter:
                trial_reporter.start_trial(messages_for_reporting)
                score = task_evaluator.evaluate(
                    dataset=dataset,
                    dataset_item_ids=eval_dataset_item_ids,
                    metric=metric,
                    evaluated_task=llm_task,
                    num_threads=self.n_threads,
                    project_name=self.project_name,
                    experiment_config=trial_config,
                    optimization_id=optimization_id,
                    verbose=self.verbose,
                )
                trial_reporter.set_score(baseline_score, score)
            logger.debug(f"Trial {trial.number} score: {score:.4f}")

            # Trial results
            trial_config = {
                "demo_examples": demo_examples,
                "message_list_with_placeholder": fewshot_prompt_template.message_list_with_placeholder,
                "message_list": messages_for_reporting,
            }
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
        sampler = optuna.samplers.TPESampler(seed=self.seed)
        study = optuna.create_study(direction="maximize", sampler=sampler)

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

        if best_score <= baseline_score:
            best_score = baseline_score
            best_prompt = prompt.get_messages()
        else:
            best_prompt = best_trial.user_attrs["config"]["message_list"]

        reporting.display_result(
            initial_score=baseline_score,
            best_score=best_score,
            best_prompt=best_prompt,
            verbose=self.verbose,
            tools=getattr(prompt, "tools", None),
        )

        return optimization_result.OptimizationResult(
            optimizer=self.__class__.__name__,
            prompt=best_prompt,
            initial_prompt=prompt.get_messages(),
            initial_score=baseline_score,
            score=best_score,
            metric_name=metric.__name__,
            details={
                "initial_score": baseline_score,
                "chat_messages": (
                    best_trial.user_attrs["config"]["message_list"]
                    if best_trial.user_attrs["config"]
                    else []
                ),
                "prompt_parameter": best_trial.user_attrs["config"],
                # "n_examples": best_n_examples,
                "example_indices": best_example_indices,
                "trial_number": best_trial.number,
                "total_trials": n_trials,
                "rounds": [],
                "stopped_early": False,
                "metric_name": metric.__name__,
                "model": self.model,
                "temperature": self.model_parameters.get("temperature"),
            },
            history=optuna_history_processed,
            llm_calls=self.llm_call_counter,
            tool_calls=self.tool_call_counter,
            dataset_id=dataset.id,
            optimization_id=optimization_id,
        )

    def optimize_prompt(  # type: ignore
        self,
        prompt: chat_prompt.ChatPrompt,
        dataset: Dataset,
        metric: Callable,
        experiment_config: dict | None = None,
        n_samples: int | None = None,
        auto_continue: bool = False,
        agent_class: type[OptimizableAgent] | None = None,
        project_name: str = "Optimization",
        max_trials: int = 10,
        optimization_id: str | None = None,
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

        Returns:
            OptimizationResult: Result of the optimization
        """
        # Use base class validation and setup methods
        self._validate_optimization_inputs(prompt, dataset, metric)
        self.agent_class = self._setup_agent_class(prompt, agent_class)

        # Set project name from parameter
        self.project_name = project_name

        optimization = None
        try:
            optimization = self.opik_client.create_optimization(
                dataset_name=dataset.name,
                objective_name=metric.__name__,
                name=self.name,
                metadata=self._build_optimization_config(),
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
            prompt.get_messages(),
            optimizer_config={
                "optimizer": self.__class__.__name__,
                "metric": metric.__name__,
                "max_trials": max_trials,
                "n_samples": n_samples,
            },
            verbose=self.verbose,
            tools=getattr(prompt, "tools", None),
        )

        utils.disable_experiment_reporting()

        # Step 1. Compute the baseline evaluation
        with reporting.display_evaluation(
            message="First we will establish the baseline performance:",
            verbose=self.verbose,
        ) as eval_report:
            baseline_score = self._evaluate_prompt(
                prompt,
                dataset=dataset,
                metric=metric,
                n_samples=n_samples,
                optimization_id=(optimization.id if optimization is not None else None),
            )

            eval_report.set_score(baseline_score)

        # Step 2. Create the few-shot prompt template
        with reporting.creation_few_shot_prompt_template(
            verbose=self.verbose
        ) as fewshot_template_report:
            fewshot_template = self._create_fewshot_prompt_template(
                model=self.model,
                prompt=prompt,
                few_shot_examples=[
                    {k: v for k, v in item.items() if k != "id"}
                    for item in dataset.get_items(nb_samples=10)
                ],
            )

            fewshot_template_report.set_fewshot_template(fewshot_template)

        # Step 3. Start the optimization process
        result = self._run_optimization(
            prompt=prompt,
            fewshot_prompt_template=fewshot_template,
            dataset=dataset,
            metric=metric,
            baseline_score=baseline_score,
            optimization_id=optimization.id if optimization is not None else None,
            experiment_config=experiment_config,
            n_trials=max_trials,
            n_samples=n_samples,
        )
        if optimization:
            self._update_optimization(optimization, status="completed")

        utils.enable_experiment_reporting()
        return result

    def _evaluate_prompt(
        self,
        prompt: chat_prompt.ChatPrompt,
        dataset: opik.Dataset,
        metric: Callable,
        n_samples: int | None = None,
        dataset_item_ids: list[str] | None = None,
        experiment_config: dict | None = None,
        optimization_id: str | None = None,
        **kwargs: Any,
    ) -> float:
        """
        Args:
            dataset: Opik Dataset to evaluate the prompt on
            metric: Metric function to evaluate on, should have the arguments `dataset_item` and `llm_output`
            dataset_item_ids: Optional list of dataset item IDs to evaluate
            experiment_config: Optional configuration for the experiment
            optimization_id: Optional ID of the optimization
            n_samples: Optional number of items to test in the dataset
        Returns:
            float: The evaluation score
        """
        llm_task = self._build_task_from_messages(prompt, prompt.get_messages())

        if n_samples is not None:
            if dataset_item_ids is not None:
                raise Exception("Can't use n_samples and dataset_item_ids")

            all_ids = [dataset_item["id"] for dataset_item in dataset.get_items()]
            n_samples = min(n_samples, len(all_ids))
            dataset_item_ids = random.sample(all_ids, n_samples)

        configuration_updates = helpers.drop_none(
            {
                "n_samples": n_samples,
                "dataset_item_ids": dataset_item_ids,
            }
        )
        additional_metadata = (
            {"optimization_id": optimization_id} if optimization_id else None
        )
        experiment_config = self._prepare_experiment_config(
            prompt=prompt,
            dataset=dataset,
            metric=metric,
            experiment_config=experiment_config,
            configuration_updates=configuration_updates,
            additional_metadata=additional_metadata,
        )

        logger.debug("Starting FewShotBayesian evaluation...")
        score = task_evaluator.evaluate(
            dataset=dataset,
            dataset_item_ids=dataset_item_ids,
            metric=metric,
            evaluated_task=llm_task,
            num_threads=self.n_threads,
            project_name=experiment_config.get("project_name"),
            experiment_config=experiment_config,
            optimization_id=optimization_id,
            verbose=self.verbose,
        )
        logger.debug(f"Evaluation score: {score:.4f}")

        return score

    def _build_task_from_messages(
        self,
        prompt: chat_prompt.ChatPrompt,
        messages: list[dict[str, str]],
        few_shot_examples: str | None = None,
    ) -> Callable[[dict[str, Any]], dict[str, Any]]:
        new_prompt = prompt.copy()
        new_prompt.set_messages(messages)
        agent = self.agent_class(new_prompt)

        def llm_task(dataset_item: dict[str, Any]) -> dict[str, Any]:
            """
            Process a single dataset item through the LLM task.

            Args:
                dataset_item: Dictionary containing the dataset item data

            Returns:
                Dictionary containing the LLM's response
            """
            messages = new_prompt.get_messages(dataset_item)

            if few_shot_examples:
                for message in messages:
                    message["content"] = message["content"].replace(
                        FEW_SHOT_EXAMPLE_PLACEHOLDER, few_shot_examples
                    )

            result = agent.invoke(messages, seed=self.seed)

            # Add tags to trace for optimization tracking
            if self.current_optimization_id:
                opik_context.update_current_trace(
                    tags=[self.current_optimization_id, "Evaluation"]
                )

            return {"llm_output": result}

        return llm_task
