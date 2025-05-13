import random
from typing import Any, Dict, List, Tuple, Union, Optional, Literal
import opik
import optuna
import optuna.samplers
import logging
import json

from opik import Dataset
from opik_optimizer.optimization_config import mappers

from opik_optimizer.optimization_config.configs import TaskConfig, MetricConfig
from opik_optimizer import base_optimizer

from . import prompt_parameter
from . import prompt_templates
from .. import _throttle
from .. import optimization_result, task_evaluator

import litellm

from opik.evaluation.models.litellm import opik_monitor as opik_litellm_monitor

_limiter = _throttle.get_rate_limiter_for_current_opik_installation()

logger = logging.getLogger(__name__)

@_throttle.rate_limited(_limiter)
def _call_model(model, messages, seed, model_kwargs):
    model_kwargs = opik_litellm_monitor.try_add_opik_monitoring_to_params(model_kwargs)

    response = litellm.completion(
        model=model,
        messages=messages,
        seed=seed,
        **model_kwargs,
    )

    return response


class FewShotBayesianOptimizer(base_optimizer.BaseOptimizer):
    def __init__(
        self,
        model: str,
        project_name: Optional[str] = None,
        min_examples: int = 2,
        max_examples: int = 8,
        seed: int = 42,
        n_threads: int = 8,
        n_initial_prompts: int = 5,
        n_iterations: int = 10,
        **model_kwargs,
    ) -> None:
        super().__init__(model, project_name, **model_kwargs)
        self.min_examples = min_examples
        self.max_examples = max_examples
        self.seed = seed
        self.n_threads = n_threads
        self.n_initial_prompts = n_initial_prompts
        self.n_iterations = n_iterations
        self._opik_client = opik.Opik()
        logger.debug(f"Initialized FewShotBayesianOptimizer with model: {model}")

    def _split_dataset(
        self, dataset: List[Dict[str, Any]], train_ratio: float
    ) -> Tuple[List[Dict[str, Any]], List[Dict[str, Any]]]:
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

    def _optimize_prompt(   
        self,
        dataset: Union[str, Dataset],
        metric_config: MetricConfig,
        task_config: TaskConfig,
        n_trials: int = 10,
        optimization_id: Optional[str] = None,
        experiment_config: Optional[Dict] = None,
        n_samples: int = None,
    ) -> optimization_result.OptimizationResult:
        random.seed(self.seed)

        if not task_config.use_chat_prompt:
            raise ValueError(
                "Few-shot Bayesian optimization is only supported for chat prompts."
            )

        opik_dataset: opik.Dataset = dataset

        # Load the dataset
        if isinstance(dataset, str):
            opik_dataset = self._opik_client.get_dataset(dataset)
            dataset_items = opik_dataset.get_items()
        else:
            opik_dataset = dataset
            dataset_items = opik_dataset.get_items()

        experiment_config = experiment_config or {}
        base_experiment_config = {  # Base config for reuse
            **experiment_config,
            **{
                "optimizer": self.__class__.__name__,
                "metric": metric_config.metric.name,
                "dataset": opik_dataset.name,
                "configuration": {},
            },
        }

        # Evaluate Initial (Zero-Shot) Prompt
        logger.info("Evaluating initial (zero-shot) prompt...")
        initial_instruction = task_config.instruction_prompt
        zero_shot_param = prompt_parameter.ChatPromptParameter(
            name="zero_shot_prompt",
            instruction=initial_instruction,
            task_input_parameters=task_config.input_dataset_fields,
            task_output_parameter=task_config.output_dataset_field,
            demo_examples=[],  # No examples
        )
        zero_shot_llm_task = self._build_task_from_prompt_template(
            zero_shot_param.as_template()
        )

        initial_eval_config = base_experiment_config.copy()
        initial_eval_config["configuration"]["prompt"] = initial_instruction
        initial_eval_config["configuration"]["n_examples"] = 0

        # Determine dataset item IDs for evaluation (initial and trials)
        all_dataset_item_ids = [item["id"] for item in dataset_items]
        eval_dataset_item_ids = all_dataset_item_ids
        if n_samples is not None and n_samples < len(all_dataset_item_ids):
            eval_dataset_item_ids = random.sample(all_dataset_item_ids, n_samples)
            logger.info(f"Using {n_samples} samples for evaluations.")
        else:
            logger.info(
                f"Using all {len(all_dataset_item_ids)} samples for evaluations."
            )

        initial_score = task_evaluator.evaluate(
            dataset=opik_dataset,
            dataset_item_ids=eval_dataset_item_ids,
            metric_config=metric_config,
            evaluated_task=zero_shot_llm_task,
            num_threads=self.n_threads,
            project_name=self.project_name,
            experiment_config=initial_eval_config,
            optimization_id=optimization_id,
        )
        logger.info(f"Initial (zero-shot) score: {initial_score:.4f}")

        # Start Optuna Study
        logger.info("Starting Optuna study for Few-Shot Bayesian Optimization...")

        def optimization_objective(trial: optuna.Trial) -> float:
            n_examples = trial.suggest_int(
                "n_examples", self.min_examples, self.max_examples
            )
            example_indices = [
                trial.suggest_categorical(f"example_{i}", list(range(len(dataset_items))))
                for i in range(n_examples)
            ]
            trial.set_user_attr("example_indices", example_indices)

            instruction = task_config.instruction_prompt
            demo_examples = [dataset_items[idx] for idx in example_indices]

            processed_demo_examples = []
            for example in demo_examples:
                processed_example = {}
                for key, value in example.items():
                    processed_example[key] = str(value)
                processed_demo_examples.append(processed_example)

            param = prompt_parameter.ChatPromptParameter(
                name=f"trial_{trial.number}_prompt",
                instruction=instruction,
                task_input_parameters=task_config.input_dataset_fields,
                task_output_parameter=task_config.output_dataset_field,
                demo_examples=processed_demo_examples,
            )

            llm_task = self._build_task_from_prompt_template(param.as_template())

            # Log trial config
            trial_config = base_experiment_config.copy()
            trial_config["configuration"]["prompt"] = instruction  # Base instruction
            trial_config["configuration"][
                "examples"
            ] = processed_demo_examples  # Log stringified examples
            trial_config["configuration"]["n_examples"] = n_examples
            trial_config["configuration"]["example_indices"] = example_indices

            logger.debug(
                f"Trial {trial.number}: n_examples={n_examples}, indices={example_indices}"
            )
            logger.debug(f"Evaluating trial {trial.number}...")

            score = task_evaluator.evaluate(
                dataset=opik_dataset,
                dataset_item_ids=eval_dataset_item_ids,
                metric_config=metric_config,
                evaluated_task=llm_task,
                num_threads=self.n_threads,
                project_name=self.project_name,
                experiment_config=trial_config,
                optimization_id=optimization_id,
            )
            logger.debug(f"Trial {trial.number} score: {score:.4f}")

            trial.set_user_attr("score", score)
            trial.set_user_attr("param", param)
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

        sampler = optuna.samplers.TPESampler(seed=self.seed)
        study = optuna.create_study(direction="maximize", sampler=sampler)
        study.optimize(optimization_objective, n_trials=n_trials)
        logger.info("Optuna study finished.")

        best_trial = study.best_trial
        best_score = best_trial.value
        best_n_examples = best_trial.params["n_examples"]
        best_example_indices = best_trial.user_attrs.get("example_indices", [])
        best_param: prompt_parameter.ChatPromptParameter = best_trial.user_attrs[
            "param"
        ]

        chat_messages_list = best_param.as_template().format()
        main_prompt_string = best_param.instruction

        return optimization_result.OptimizationResult(
            prompt=main_prompt_string,
            score=best_score,
            metric_name=metric_config.metric.name,
            details={
                "prompt_type": "chat" if task_config.use_chat_prompt else "non-chat",
                "chat_messages": chat_messages_list,
                "prompt_parameter": best_param,
                "n_examples": best_n_examples,
                "example_indices": best_example_indices,
                "trial_number": best_trial.number,
                "initial_score": initial_score,
                "total_trials": n_trials,
                "rounds": [],
                "stopped_early": False,
                "metric_config": metric_config.dict(),
                "task_config": task_config.dict(),
                "model": self.model,
                "temperature": self.model_kwargs.get("temperature"),
            },
        )

    def optimize_prompt(
        self,
        dataset: Union[str, Dataset],
        metric_config: MetricConfig,
        task_config: TaskConfig,
        n_trials: int = 10,
        experiment_config: Optional[Dict] = None,
        n_samples: int = None,
    ) -> optimization_result.OptimizationResult:
        optimization = None
        try:
            optimization = self._opik_client.create_optimization(
                dataset_name=dataset.name,
                objective_name=metric_config.metric.name,
            )
        except Exception:
            logger.warning(
                "Opik server does not support optimizations. Please upgrade opik."
            )
            optimization = None

        try:
            result = self._optimize_prompt(
                optimization_id=optimization.id if optimization is not None else None,
                dataset=dataset,
                metric_config=metric_config,
                task_config=task_config,
                n_trials=n_trials,
                experiment_config=experiment_config,
                n_samples=n_samples,
            )
            if optimization:
                self.update_optimization(optimization, status="completed")
            return result
        except Exception as e:
            if optimization:
                self.update_optimization(optimization, status="cancelled")
            logger.error(f"FewShotBayesian optimization failed: {e}", exc_info=True)
            raise e

    def evaluate_prompt(
        self,
        prompt: List[Dict[Literal["role", "content"], str]],
        dataset: opik.Dataset,
        metric_config: MetricConfig,
        task_config: Optional[TaskConfig] = None,
        dataset_item_ids: Optional[List[str]] = None,
        experiment_config: Optional[Dict] = None,
        n_samples: int = None,
    ) -> float:

        if isinstance(prompt, str):
            if task_config is None:
                raise ValueError(
                    "To use a string prompt, please pass in task_config to evaluate_prompt()"
                )

            questions = {
                field: ("{{%s}}" % field) for field in task_config.input_dataset_fields
            }
            prompt = [
                {"role": "system", "content": prompt},
                {"role": "user", "content": json.dumps(questions)},
            ]

        # Ensure prompt is correctly formatted
        if not all(
            isinstance(item, dict) and "role" in item and "content" in item
            for item in prompt
        ):
            raise ValueError(
                "A ChatPrompt must be a list of dictionaries with 'role' and 'content' keys."
            )

        template = prompt_templates.ChatPromptTemplate(
            prompt, validate_placeholders=False
        )
        llm_task = self._build_task_from_prompt_template(template)

        experiment_config = experiment_config or {}
        experiment_config = {
            **experiment_config,
            **{
                "optimizer": self.__class__.__name__,
                "metric": metric_config.metric.name,
                "dataset": dataset.name,
                "configuration": {
                    "examples": prompt,
                },
            },
        }

        if n_samples is not None:
            if dataset_item_ids is not None:
                raise Exception("Can't use n_samples and dataset_item_ids")

            all_ids = [dataset_item["id"] for dataset_item in dataset.get_items()]
            dataset_item_ids = random.sample(all_ids, n_samples)

        logger.debug(f"Starting FewShotBayesian evaluation...")
        score = task_evaluator.evaluate(
            dataset=dataset,
            dataset_item_ids=dataset_item_ids,
            metric_config=metric_config,
            evaluated_task=llm_task,
            num_threads=self.n_threads,
            project_name=self.project_name,
            experiment_config=experiment_config,
        )
        logger.debug(f"Evaluation score: {score:.4f}")

        return score

    def _build_task_from_prompt_template(
        self, template: prompt_templates.ChatPromptTemplate
    ):
        def llm_task(dataset_item: Dict[str, Any]) -> Dict[str, Any]:
            prompt_ = template.format(**dataset_item)

            response = _call_model(
                model=self.model,
                messages=prompt_,
                seed=self.seed,
                model_kwargs=self.model_kwargs,
            )

            return {
                mappers.EVALUATED_LLM_TASK_OUTPUT: response.choices[0].message.content
            }

        return llm_task
