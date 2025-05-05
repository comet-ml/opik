import random
from typing import Any, Dict, List, Tuple, Union, Optional, Callable, Literal
import openai
import opik
from opik_optimizer import optimization_result, task_evaluator
import optuna

from opik.integrations.openai import track_openai
from opik_optimizer.optimization_config import mappers
from opik_optimizer import optimization_dsl, base_optimizer
from . import prompt_parameter
from . import prompt_templates
from .._throttle import RateLimiter, rate_limited

limiter = RateLimiter(max_calls_per_second=15)


@rate_limited(limiter)
def _call_model(client, model, messages, seed, **model_kwargs):
    response = client.chat.completions.create(
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

        self._openai_client = track_openai(
            openai.OpenAI(), project_name=self.project_name
        )
        self._opik_client = opik.Opik()

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

    def optimize_prompt(
        self,
        config: optimization_dsl.OptimizationConfig,
        n_trials: int = 10,
        experiment_config: Optional[Dict] = None,
    ) -> optimization_result.OptimizationResult:
        random.seed(self.seed)

        if not config.task.use_chat_prompt:
            raise ValueError(
                "Few-shot Bayesian optimization is only supported for chat prompts."
            )

        opik_dataset: opik.Dataset = config.dataset

        # Load the dataset
        if isinstance(config.dataset, str):
            opik_dataset = self._opik_client.get_dataset(config.dataset)
            dataset = opik_dataset.get_items()
        else:
            opik_dataset = config.dataset
            dataset = opik_dataset.get_items()

        # train_set, validation_set = _split_dataset(
        #     dataset, train_ratio=1.0
        # )  # TODO: make configurable

        experiment_config = experiment_config or {}
        experiment_config = {
            **experiment_config,
            **{
                "optimizer": self.__class__.__name__,
                "metric": config.objective.metric.name,
                "dataset": opik_dataset.name,
                "configuration": {},
            },
        }

        def optimization_objective(trial: optuna.Trial) -> float:
            n_examples = trial.suggest_int(
                "n_examples", self.min_examples, self.max_examples
            )

            example_indices = [
                trial.suggest_categorical(f"example_{i}", list(range(len(dataset))))
                for i in range(n_examples)
            ]

            instruction = config.task.instruction_prompt
            demo_examples = [dataset[idx] for idx in example_indices]

            param = prompt_parameter.ChatPromptParameter(
                name="few_shot_examples_chat_prompt",
                instruction=instruction,
                task_input_parameters=config.task.input_dataset_fields,
                task_output_parameter=config.task.output_dataset_field,
                demo_examples=demo_examples,
            )

            llm_task = self._build_task_from_prompt_template(param.as_template())

            experiment_config["configuration"]["prompt"] = instruction
            experiment_config["configuration"]["examples"] = demo_examples

            score = task_evaluator.evaluate(
                dataset=opik_dataset,
                dataset_item_ids=[example["id"] for example in dataset],
                metric_config=config.objective,
                evaluated_task=llm_task,
                num_threads=self.n_threads,
                project_name=self.project_name,
                experiment_config=experiment_config,
            )

            trial.set_user_attr("score", score)
            trial.set_user_attr("param", param)
            return score

        study = optuna.create_study(direction=config.optimization_direction)
        study.optimize(optimization_objective, n_trials=n_trials)

        best_trial = study.best_trial
        best_n_examples = best_trial.params["n_examples"]

        best_param: prompt_parameter.ChatPromptParameter = best_trial.user_attrs[
            "param"
        ]

        return optimization_result.OptimizationResult(
            prompt=best_param.as_template().format(),
            score=best_trial.user_attrs["score"],
            metric_name=config.objective.metric.name,
            metadata={
                "prompt_parameter": best_param,
                "n_examples": best_n_examples,
                "example_indices": best_trial.params.get("example_indices", []),
                "trial_number": best_trial.number,
                "prompt_template": best_param.as_template(),
            },
            best_prompt=None,
            best_score=None,
            best_metric_name=None,
            best_details=None,
            all_results=None,
            history=[],
            metric=None,
        )

    def evaluate_prompt(
        self,
        prompt: List[Dict[Literal["role", "content"], str]],
        dataset: opik.Dataset,
        metric_config: optimization_dsl.MetricConfig,
        dataset_item_ids: Optional[List[str]] = None,
        experiment_config: Optional[Dict] = None,
    ) -> float:

        # Ensure prompt is correctly formatted
        if not all(
            isinstance(item, dict) and "role" in item and "content" in item
            for item in prompt
        ):
            raise ValueError(
                "Prompt must be a list of dictionaries with 'role' and 'content' keys."
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

        score = task_evaluator.evaluate(
            dataset=dataset,
            dataset_item_ids=dataset_item_ids,
            metric_config=metric_config,
            evaluated_task=llm_task,
            num_threads=self.n_threads,
            project_name=self.project_name,
            experiment_config=experiment_config,
        )

        return score

    def _build_task_from_prompt_template(
        self, template: prompt_templates.ChatPromptTemplate
    ):
        def llm_task(dataset_item: Dict[str, Any]) -> Dict[str, Any]:
            prompt_ = template.format(**dataset_item)

            response = _call_model(
                client=self._openai_client,
                model=self.model,
                messages=prompt_,
                seed=self.seed,
                **self.model_kwargs,
            )

            return {
                mappers.EVALUATED_LLM_TASK_OUTPUT: response.choices[0].message.content
            }

        return llm_task


# def _split_dataset(
#     dataset: List[Dict[str, Any]], train_ratio: float
# ) -> Tuple[List[Dict[str, Any]], List[Dict[str, Any]]]:
#     shuffled = random.sample(dataset, len(dataset))
#     train_set = shuffled[: int(train_ratio * len(dataset))]
#     validation_set = shuffled[int(train_ratio * len(dataset)) :]

#     return train_set, validation_set
