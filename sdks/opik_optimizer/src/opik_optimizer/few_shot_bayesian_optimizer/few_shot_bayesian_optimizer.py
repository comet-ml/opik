import json
import logging
import random
from datetime import datetime
from typing import Any, Dict, List, Optional, Tuple

import litellm
import opik
import optuna
import optuna.samplers
from opik import Dataset
from opik.evaluation.models.litellm import opik_monitor as opik_litellm_monitor
from pydantic import BaseModel

from opik_optimizer import base_optimizer
from opik_optimizer.optimization_config import mappers
from opik_optimizer.optimization_config.configs import MetricConfig

from .. import _throttle, optimization_result, task_evaluator, utils
from ..optimization_config import chat_prompt
from . import prompt_parameter, reporting

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
    - Make sure to include the variables as part of this string so we can before string formatting with actual examples
    - Ensure the format of the few shot examples are consistent with how the model will be called

Return your output as a JSON object with:

- message_list_with_placeholder: the updated list with "FEW_SHOT_EXAMPLE_PLACEHOLDER" inserted.
- example_template: a string template using the fields provided in the examples (you don't need to use all of them)

Respond only with the JSON object. Do not include any explanation or extra text.
"""

from opik.evaluation import report
report.display_experiment_results = lambda *args, **kwargs: None
report.display_experiment_link = lambda *args, **kwargs: None

class FewShotPromptTemplate(BaseModel):
    message_list_with_placeholder: List[Dict[str, str]]
    example_template: str

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
        verbose: int = 1,
        **model_kwargs,
    ) -> None:
        super().__init__(model, project_name, **model_kwargs)
        self.min_examples = min_examples
        self.max_examples = max_examples
        self.seed = seed
        self.n_threads = n_threads
        self.n_initial_prompts = n_initial_prompts
        self.n_iterations = n_iterations
        self.verbose = verbose
        if verbose == 0:
            logger.setLevel(logging.WARNING)
        elif verbose == 1:
            logger.setLevel(logging.INFO)
        elif verbose == 2:
            logger.setLevel(logging.DEBUG)
        
        self._opik_client = opik.Opik()
        self.llm_call_counter = 0
        logger.debug(f"Initialized FewShotBayesianOptimizer with model: {model}")

    @_throttle.rate_limited(_limiter)
    def _call_model(self, model, messages, seed, model_kwargs):
        self.llm_call_counter += 1

        current_model_kwargs = self.model_kwargs.copy()
        current_model_kwargs.update(model_kwargs)

        filtered_call_kwargs = current_model_kwargs.copy()
        filtered_call_kwargs.pop('n_trials', None)
        filtered_call_kwargs.pop('n_samples', None)
        filtered_call_kwargs.pop('n_iterations', None)
        filtered_call_kwargs.pop('min_examples', None)
        filtered_call_kwargs.pop('max_examples', None)
        filtered_call_kwargs.pop('n_initial_prompts', None)

        final_params_for_litellm = opik_litellm_monitor.try_add_opik_monitoring_to_params(filtered_call_kwargs)

        response = litellm.completion(
            model=self.model,
            messages=messages,
            seed=seed,
            num_retries=6,
            **final_params_for_litellm,
        )
        return response

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

    def _create_fewshot_prompt_template(
        self,
        model: str,
        prompt: chat_prompt.ChatPrompt,
        few_shot_examples: List[Dict[str, Any]]
    ) -> FewShotPromptTemplate:
        """
        During this step we update the system prompt to include few-shot examples.
        """
        user_message = {
            "message_list": prompt.formatted_messages,
            "examples": few_shot_examples
        }
        
        messages = [
            {"role": "system", "content": SYSTEM_PROMPT_TEMPLATE},
            {"role": "user", "content": json.dumps(user_message)},
        ]
        
        logger.debug(f"fewshot_prompt_template - Calling LLM with: {messages}")
        response = self._call_model(
            model,
            messages,
            self.seed,
            self.model_kwargs
        )
        logger.debug(f"fewshot_prompt_template - LLM response: {response}")

        try:
            res = json.loads(response["choices"][0]["message"]["content"])
            return FewShotPromptTemplate(**res)
        except Exception as e:
            logger.error(f"Failed to compute few-shot prompt template: {e} - response: {response}")
            raise

    def _run_optimization(
        self,
        fewshot_prompt_template: FewShotPromptTemplate,
        dataset: Dataset,
        metric_config: MetricConfig,
        n_trials: int = 10,
        baseline_score: Optional[float] = None,
        optimization_id: Optional[str] = None,
        experiment_config: Optional[Dict] = None,
        n_samples: int = None,
    ) -> optimization_result.OptimizationResult:
        reporting.start_optimization_run()

        random.seed(self.seed)
        self.llm_call_counter = 0
        
        # Load the dataset
        dataset_items = dataset.get_items()
        all_dataset_item_ids = [item["id"] for item in dataset_items]
        eval_dataset_item_ids = all_dataset_item_ids
        if n_samples is not None and n_samples < len(dataset_items):
            eval_dataset_item_ids = random.sample(all_dataset_item_ids, n_samples)
        
        # Define the experiment configuration
        experiment_config = experiment_config or {}
        base_experiment_config = {  # Base config for reuse
            **experiment_config,
            **{
                "optimizer": self.__class__.__name__,
                "metric": metric_config.metric.name,
                "dataset": dataset.name,
                "configuration": {},
            },
        }

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

                processed_demo_examples.append(
                    fewshot_prompt_template.example_template.format(**processed_example)
                )
            few_shot_examples = "\n\n".join(processed_demo_examples)
            
            messages = [{
                "role": x["role"],
                "content": x["content"].replace(FEW_SHOT_EXAMPLE_PLACEHOLDER, few_shot_examples)
            } for x in fewshot_prompt_template.message_list_with_placeholder]
            
            llm_task = self._build_task_from_messages(messages)

            # Log trial config
            trial_config = base_experiment_config.copy()
            trial_config["configuration"]["prompt"] = messages  # Base instruction
            trial_config["configuration"][
                "examples"
            ] = processed_demo_examples  # Log stringified examples
            trial_config["configuration"]["n_examples"] = n_examples
            trial_config["configuration"]["example_indices"] = example_indices

            logger.debug(
                f"Trial {trial.number}: n_examples={n_examples}, indices={example_indices}"
            )
            logger.debug(f"Evaluating trial {trial.number}...")

            with reporting.start_optimization_trial(trial.number, baseline_score) as trial_reporter:
                score = task_evaluator.evaluate(
                    dataset=dataset,
                    dataset_item_ids=eval_dataset_item_ids,
                    metric_config=metric_config,
                    evaluated_task=llm_task,
                    num_threads=self.n_threads,
                    project_name=self.project_name,
                    experiment_config=trial_config,
                    optimization_id=optimization_id,
                    verbose=self.verbose,
                )
                trial_reporter.set_score(score)
            logger.debug(f"Trial {trial.number} score: {score:.4f}")

            # Trial results
            trial_config = {
                "demo_examples": demo_examples,
                "message_list_with_placeholder": fewshot_prompt_template.message_list_with_placeholder,
                "message_list": messages
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
            optimization_objective,
            n_trials=n_trials,
            show_progress_bar=False
        )
        logger.info("Optuna study finished.")

        optuna_history_processed = []
        for trial_idx, trial in enumerate(study.trials):
            if trial.state == optuna.trial.TrialState.COMPLETE:
                param_obj: Optional[prompt_parameter.ChatPromptParameter] = trial.user_attrs.get("param")
                prompt_cand_display = None # Default to None
                if param_obj and hasattr(param_obj, 'as_template') and callable(param_obj.as_template):
                    try:
                        # .format() on ChatPromptTemplate returns the list of messages
                        chat_messages_for_history = param_obj.as_template().format()
                        prompt_cand_display = json.dumps(chat_messages_for_history) 
                    except Exception as e_param_format:
                        logger.warning(f"Trial {trial.number}: Error formatting prompt from param_obj: {e_param_format}")
                        prompt_cand_display = "Error: Could not format prompt content."
                elif not param_obj:
                    logger.warning(f"Trial {trial.number}: 'param' object not found in user_attrs.")
                    prompt_cand_display = "Error: Prompt data missing in trial."
                else:
                    logger.warning(f"Trial {trial.number}: 'param' object is not of expected type or lacks methods.")
                    prompt_cand_display = "Error: Invalid prompt data structure in trial."

                score_val = trial.value # This can be None if trial failed to produce a score
                duration_val = None
                if trial.datetime_complete and trial.datetime_start:
                    duration_val = (trial.datetime_complete - trial.datetime_start).total_seconds()

                iter_detail = {
                    "iteration": trial.number + 1, 
                    "timestamp": trial.datetime_start.isoformat() if trial.datetime_start else datetime.now().isoformat(),
                    "prompt_candidate": prompt_cand_display,
                    "parameters_used": { 
                        "optuna_params": trial.user_attrs.get("config", {}), 
                        "example_indices": trial.user_attrs.get("example_indices", []) # Default to empty list
                    },
                    "scores": [{
                        "metric_name": metric_config.metric.name, 
                        "score": score_val, # Can be None
                        "opik_evaluation_id": None # TODO
                    }],
                    "tokens_used": None, # TODO
                    "cost": None, # TODO
                    "duration_seconds": duration_val,
                }
                optuna_history_processed.append(iter_detail)
            else:
                logger.warning(f"Skipping trial {trial.number} from history due to state: {trial.state}. Value: {trial.value}")

        best_trial = study.best_trial
        best_score = best_trial.value
        #best_n_examples = best_trial.params["n_examples"]
        best_example_indices = best_trial.user_attrs.get("example_indices", [])
        # best_param: prompt_parameter.ChatPromptParameter = best_trial.user_attrs[
        #     "param"
        # ]

        # chat_messages_list = best_param.as_template().format()
        # main_prompt_string = best_param.instruction

        return optimization_result.OptimizationResult(
            optimizer=self.__class__.__name__,
            prompt=best_trial.user_attrs["config"]["message_list"],
            score=best_score,
            metric_name=metric_config.metric.name,
            details={
                "chat_messages": best_trial.user_attrs["config"]["message_list"],
                "prompt_parameter": best_trial.user_attrs["config"],
                #"n_examples": best_n_examples,
                "example_indices": best_example_indices,
                "trial_number": best_trial.number,
                "total_trials": n_trials,
                "rounds": [],
                "stopped_early": False,
                "metric_config": metric_config.model_dump(),
                "model": self.model,
                "temperature": self.model_kwargs.get("temperature"),
            },
            history=optuna_history_processed,
            llm_calls=self.llm_call_counter
        )

    def optimize_prompt(
        self,
        dataset: Dataset,
        prompt: chat_prompt.ChatPrompt,
        metric_config: MetricConfig,
        n_trials: int = 10,
        experiment_config: Optional[Dict] = None,
        n_samples: int = None
    ) -> optimization_result.OptimizationResult:
        optimization = None
        try:
            optimization = self._opik_client.create_optimization(
                dataset_name=dataset.name,
                objective_name=metric_config.metric.name,
                metadata={"optimizer": self.__class__.__name__},
            )
        except Exception:
            logger.warning(
                "Opik server does not support optimizations. Please upgrade opik."
            )
            optimization = None

        try:
            # Start experiment reporting
            reporting.display_header("Few-Shot Bayesian Optimizer")
            reporting.display_configuration(
                prompt.formatted_messages,
                optimizer_config={
                    "optimizer": self.__class__.__name__,
                    "metric": metric_config.metric.name,
                    "n_trials": n_trials,
                    "n_samples": n_samples
                }
            )

            utils.disable_experiment_reporting()

            # Step 1. Compute the baseline evaluation
            with reporting.display_evaluation(message="First we will establish the baseline performance:") as eval_report:
                baseline_score = self.evaluate_prompt(
                    prompt=prompt,
                    dataset=dataset,
                    metric_config=metric_config,
                    n_samples=n_samples,
                    optimization_id=optimization.id if optimization is not None else None
                )

                eval_report.set_score(baseline_score)
            
            # Step 2. Create the few-shot prompt template
            with reporting.creation_few_shot_prompt_template() as fewshot_template_report:
                fewshot_template = self._create_fewshot_prompt_template(
                    model=self.model,
                    prompt=prompt,
                    few_shot_examples=[{k: v for k, v in item.items() if k != 'id'} 
                                        for item in dataset.get_items(nb_samples=10)]
                )

                fewshot_template_report.set_fewshot_template(fewshot_template)

            # Step 3. Start the optimization process
            result = self._run_optimization(
                fewshot_prompt_template=fewshot_template,
                dataset=dataset,
                metric_config=metric_config,
                optimization_id=optimization.id if optimization is not None else None,
                experiment_config=experiment_config,
                n_trials=n_trials,
                baseline_score=baseline_score,
                n_samples=n_samples,
            )
            if optimization:
                self.update_optimization(optimization, status="completed")

            utils.enable_experiment_reporting()
            return result
        except Exception as e:
            if optimization:
                self.update_optimization(optimization, status="cancelled")
            logger.error(f"FewShotBayesian optimization failed: {e}", exc_info=True)
            utils.enable_experiment_reporting()
            raise e

    def evaluate_prompt(
        self,
        prompt: chat_prompt.ChatPrompt,
        dataset: opik.Dataset,
        metric_config: MetricConfig,
        dataset_item_ids: Optional[List[str]] = None,
        experiment_config: Optional[Dict] = None,
        optimization_id: Optional[str] = None,
        n_samples: int = None,
    ) -> float:
        # Ensure prompt is correctly formatted
        if not all(
            isinstance(item, dict) and "role" in item and "content" in item
            for item in prompt.formatted_messages
        ):
            raise ValueError(
                "A ChatPrompt must be a list of dictionaries with 'role' and 'content' keys."
            )

        llm_task = self._build_task_from_messages(prompt.formatted_messages)

        experiment_config = experiment_config or {}
        experiment_config = {
            **experiment_config,
            **{
                "optimizer": self.__class__.__name__,
                "metric": metric_config.metric.name,
                "dataset": dataset.name,
                "configuration": {
                    "prompt": prompt.formatted_messages,
                },
            },
        }

        if n_samples is not None:
            if dataset_item_ids is not None:
                raise Exception("Can't use n_samples and dataset_item_ids")

            all_ids = [dataset_item["id"] for dataset_item in dataset.get_items()]
            dataset_item_ids = random.sample(all_ids, n_samples)

        logger.debug("Starting FewShotBayesian evaluation...")
        score = task_evaluator.evaluate(
            dataset=dataset,
            dataset_item_ids=dataset_item_ids,
            metric_config=metric_config,
            evaluated_task=llm_task,
            num_threads=self.n_threads,
            project_name=self.project_name,
            experiment_config=experiment_config,
            optimization_id=optimization_id,
            verbose=self.verbose,
        )
        logger.debug(f"Evaluation score: {score:.4f}")

        return score


    def _build_task_from_messages(
        self, messages: List[Dict[str, str]]
    ):
        def llm_task(dataset_item: Dict[str, Any]) -> Dict[str, Any]:
            prompt_ = [{
                "role": item["role"],
                "content": item["content"].format(**dataset_item)
            } for item in messages]

            response = self._call_model(
                model=self.model,
                messages=prompt_,
                seed=self.seed,
                model_kwargs=self.model_kwargs
            )

            return {
                mappers.EVALUATED_LLM_TASK_OUTPUT: response.choices[0].message.content
            }

        return llm_task
