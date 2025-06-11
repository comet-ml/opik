import copy
import json
import logging
import random
from datetime import datetime
from typing import Any, Callable, Dict, List, Optional, Tuple

import litellm
import opik
import optuna
import optuna.samplers
from opik import Dataset
from opik.evaluation.models.litellm import opik_monitor as opik_litellm_monitor
from pydantic import BaseModel

from opik_optimizer import base_optimizer
from opik_optimizer.optimization_config import mappers

from .. import _throttle, optimization_result, task_evaluator, utils
from ..optimization_config import chat_prompt
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
    message_list_with_placeholder: List[Dict[str, str]]
    example_template: str

class FewShotBayesianOptimizer(base_optimizer.BaseOptimizer):
    """
    The Few-Shot Bayesian Optimizer can be used to add few-shot examples to prompts. This algorithm
    employes a two stage pipeline:

    1. We generate a few-shot prompt template that is inserted can be inserted into the prompt 
       provided
    2. We use Bayesian Optimization to determine the best examples to include in the prompt.

    This algorithm is best used when you have a well defined task and would like to guide the LLM
    by providing some examples.
    """
    def __init__(
        self,
        model: str,
        project_name: Optional[str] = "Optimization",
        min_examples: int = 2,
        max_examples: int = 8,
        seed: int = 42,
        n_threads: int = 8,
        verbose: int = 1,
        **model_kwargs,
    ) -> None:
        """
        Args:
            model: The model to used to evaluate the prompt
            project_name: Optional project name for tracking
            min_examples: Minimum number of examples to include
            max_examples: Maximum number of examples to include
            seed: Random seed for reproducibility
            n_threads: Number of threads for parallel evaluation
            verbose: Controls internal logging/progress bars (0=off, 1=on).
            **model_kwargs: Additional model parameters
        """
        super().__init__(model, project_name, **model_kwargs)
        self.min_examples = min_examples
        self.max_examples = max_examples
        self.seed = seed
        self.n_threads = n_threads
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
        
        messages: List[Dict[str, str]] = [
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
            res = utils.json_to_dict(response["choices"][0]["message"]["content"])
            return FewShotPromptTemplate(
                message_list_with_placeholder=res["message_list_with_placeholder"],
                example_template=res["example_template"]
            )
        except Exception as e:
            logger.error(f"Failed to compute few-shot prompt template: {e} - response: {response}")
            raise

    def _run_optimization(
        self,
        initial_prompt: chat_prompt.ChatPrompt,
        fewshot_prompt_template: FewShotPromptTemplate,
        dataset: Dataset,
        metric: Callable,
        n_trials: int = 10,
        baseline_score: Optional[float] = None,
        optimization_id: Optional[str] = None,
        experiment_config: Optional[Dict] = None,
        n_samples: Optional[int] = None,
    ) -> optimization_result.OptimizationResult:
        reporting.start_optimization_run(verbose=self.verbose)

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
                "metric": metric.__name__,
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

                processed_demo_example=fewshot_prompt_template.example_template
                for key, value in processed_example.items():
                    try:
                        processed_demo_example=processed_demo_example.replace(f"{{{key}}}", str(value))
                    except Exception:
                        logger.error(f"Failed to format fewshot prompt template {fewshot_prompt_template} with example: {processed_example} ")
                        raise
                processed_demo_examples.append(processed_demo_example)
            few_shot_examples = "\n\n".join(processed_demo_examples)
            
            llm_task = self._build_task_from_messages(
                messages=fewshot_prompt_template.message_list_with_placeholder,
                few_shot_examples=few_shot_examples
            )

            messages_for_reporting = [{
                "role": item["role"],
                "content": item["content"].replace(FEW_SHOT_EXAMPLE_PLACEHOLDER, few_shot_examples)
            } for item in fewshot_prompt_template.message_list_with_placeholder]

            # Log trial config
            trial_config = base_experiment_config.copy()
            trial_config["configuration"]["prompt"] = messages_for_reporting  # Base instruction
            trial_config["configuration"][
                "examples"
            ] = processed_demo_examples  # Log stringified examples
            trial_config["configuration"]["n_examples"] = n_examples
            trial_config["configuration"]["example_indices"] = example_indices

            logger.debug(
                f"Trial {trial.number}: n_examples={n_examples}, indices={example_indices}"
            )
            logger.debug(f"Evaluating trial {trial.number}...")

            with reporting.start_optimization_trial(trial.number, n_trials, verbose=self.verbose) as trial_reporter:
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
                "message_list": messages_for_reporting
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
        
        optuna_history_processed = []
        for trial_idx, trial in enumerate(study.trials):
            if trial.state == optuna.trial.TrialState.COMPLETE:
                trial_config = trial.user_attrs.get("config", {})
                prompt_cand_display = trial_config.get('message_list') # Default to None
                
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
                        "metric_name": metric.__name__, 
                        "score": score_val, # Can be None
                    }],
                    "duration_seconds": duration_val,
                }
                optuna_history_processed.append(iter_detail)
            else:
                logger.warning(f"Skipping trial {trial.number} from history due to state: {trial.state}. Value: {trial.value}")

        best_trial = study.best_trial
        best_score = best_trial.value
        best_example_indices = best_trial.user_attrs.get("example_indices", [])

        if best_score <= baseline_score:
            best_score = baseline_score
            best_prompt = initial_prompt.formatted_messages
        else:
            best_prompt = best_trial.user_attrs["config"]["message_list"]

        reporting.display_result(
            initial_score=baseline_score,
            best_score=best_score,
            best_prompt=best_trial.user_attrs["config"]["message_list"],
            verbose=self.verbose
        )

        return optimization_result.OptimizationResult(
            optimizer=self.__class__.__name__,
            prompt=best_trial.user_attrs["config"]["message_list"],
            initial_prompt=initial_prompt.formatted_messages,
            initial_score=baseline_score,
            score=best_score,
            metric_name=metric.__name__,
            details={
                "initial_score": baseline_score,
                "chat_messages": best_trial.user_attrs["config"]["message_list"],
                "prompt_parameter": best_trial.user_attrs["config"],
                #"n_examples": best_n_examples,
                "example_indices": best_example_indices,
                "trial_number": best_trial.number,
                "total_trials": n_trials,
                "rounds": [],
                "stopped_early": False,
                "metric_name": metric.__name__,
                "model": self.model,
                "temperature": self.model_kwargs.get("temperature"),
            },
            history=optuna_history_processed,
            llm_calls=self.llm_call_counter
        )

    def optimize_prompt( # type: ignore
        self,
        prompt: chat_prompt.ChatPrompt,
        dataset: Dataset,
        metric: Callable,
        n_trials: int = 10,
        experiment_config: Optional[Dict] = None,
        n_samples: Optional[int] = None,
    ) -> optimization_result.OptimizationResult:
        """
        Args:
            prompt: The prompt to optimize
            dataset: Opik Dataset to optimize on
            metric: Metric function to evaluate on
            n_trials: Number of trials for Bayesian Optimization
            experiment_config: Optional configuration for the experiment, useful to log additional metadata
            n_samples: Optional number of items to test in the dataset
        
        Returns:
            OptimizationResult: Result of the optimization
        """
        if not isinstance(prompt, chat_prompt.ChatPrompt):
            raise ValueError("Prompt must be a ChatPrompt object")
        
        if not isinstance(dataset, Dataset):
            raise ValueError("Dataset must be a Dataset object")
        
        if not isinstance(metric, Callable):
            raise ValueError("Metric must be a function that takes `dataset_item` and `llm_output` as arguments.")


        optimization = None
        try:
            optimization = self._opik_client.create_optimization(
                dataset_name=dataset.name,
                objective_name=metric.__name__,
                metadata={"optimizer": self.__class__.__name__},
            )
            optimization_run_id = optimization.id
        except Exception:
            logger.warning(
                "Opik server does not support optimizations. Please upgrade opik."
            )
            optimization = None
            optimization_run_id = None

        try:
            # Start experiment reporting
            reporting.display_header(
                algorithm=self.__class__.__name__,
                optimization_id=optimization_run_id,
                dataset_id=dataset.id,
                verbose=self.verbose
            )
            reporting.display_configuration(
                prompt.formatted_messages,
                optimizer_config={
                    "optimizer": self.__class__.__name__,
                    "metric": metric.__name__,
                    "n_trials": n_trials,
                    "n_samples": n_samples
                },
                verbose=self.verbose
            )

            utils.disable_experiment_reporting()

            # Step 1. Compute the baseline evaluation
            with reporting.display_evaluation(message="First we will establish the baseline performance:", verbose=self.verbose) as eval_report:
                baseline_score = self.evaluate_prompt(
                    prompt=prompt,
                    dataset=dataset,
                    metric=metric,
                    n_samples=n_samples,
                    optimization_id=optimization.id if optimization is not None else None
                )

                eval_report.set_score(baseline_score)
            
            # Step 2. Create the few-shot prompt template
            with reporting.creation_few_shot_prompt_template(verbose=self.verbose) as fewshot_template_report:
                fewshot_template = self._create_fewshot_prompt_template(
                    model=self.model,
                    prompt=prompt,
                    few_shot_examples=[{k: v for k, v in item.items() if k != 'id'} 
                                        for item in dataset.get_items(nb_samples=10)]
                )

                fewshot_template_report.set_fewshot_template(fewshot_template)

            # Step 3. Start the optimization process
            result = self._run_optimization(
                initial_prompt=prompt,
                fewshot_prompt_template=fewshot_template,
                dataset=dataset,
                metric=metric,
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
        metric: Callable,
        dataset_item_ids: Optional[List[str]] = None,
        experiment_config: Optional[Dict] = None,
        optimization_id: Optional[str] = None,
        n_samples: Optional[int] = None,
    ) -> float:
        """
        Args:
            prompt: The prompt to evaluate
            dataset: Opik Dataset to evaluate the prompt on
            metric: Metric function to evaluate on, should have the arguments `dataset_item` and `llm_output`
            dataset_item_ids: Optional list of dataset item IDs to evaluate
            experiment_config: Optional configuration for the experiment
            optimization_id: Optional ID of the optimization
            n_samples: Optional number of items to test in the dataset
        Returns:
            float: The evaluation score
        """
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
                "metric": metric.__name__,
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
            metric=metric,
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
        self, messages: List[Dict[str, str]], few_shot_examples: Optional[str] = None
    ):
        def llm_task(dataset_item: Dict[str, Any]) -> Dict[str, Any]:
            prompt_ = copy.deepcopy(messages)
            for key, value in dataset_item.items():
                for item in prompt_:
                    item["content"] = item["content"].replace("{" + key + "}", str(value))

            if few_shot_examples:
                for item in prompt_:
                    item["content"] = item["content"].replace(FEW_SHOT_EXAMPLE_PLACEHOLDER, few_shot_examples)
            
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
