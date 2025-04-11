import random
from typing import Any, Dict, List, Tuple, Union, Optional

import openai
import opik
from opik_optimizer import optimization_result
import optuna
from opik import Dataset
from opik.evaluation.metrics import BaseMetric
from opik.integrations.openai import track_openai

from . import base_optimizer
from . import predictor, prompt_parameter, evaluator


class FewShotBayesianOptimizer(base_optimizer.BaseOptimizer):

    def __init__(
        self,
        model: str,
        project_name: Optional[str] = None,
        min_examples: int = 2,
        max_examples: int = 8,
        seed: int = 42,
        **model_kwargs,
    ):
        super().__init__(model, project_name, **model_kwargs)
        self.min_examples = min_examples
        self.max_examples = max_examples
        self.seed = seed

        self._openai_client = track_openai(
            openai.OpenAI(), project_name=self.project_name
        )
        self._opik_client = opik.Opik()

    def optimize_prompt(
        self,
        dataset: Union[str, Dataset],
        metric: BaseMetric,
        prompt: str,  # TODO: should we enforce prompt parameter for demos?
        few_shot_examples_inputs_from_dataset_columns_mapping: Union[List[str], Dict[str, str]],
        metric_inputs_from_dataset_columns_mapping: Dict[str, Any],
        metric_inputs_from_predictor_output_mapping: Dict[str, Any], 
        n_trials: int = 10,
        num_threads: int = 4,
        train_ratio: float = 0.2,
        **kwargs,
    ) -> optimization_result.OptimizationResult:
        random.seed(self.seed)

        opik_dataset: opik.Dataset

        # Load the dataset
        if isinstance(dataset, str):
            opik_dataset = self._opik_client.get_dataset(dataset)
            dataset = opik_dataset.get_items()
        else:
            opik_dataset = dataset
            dataset = dataset.get_items()

        train_set, validation_set = _split_dataset(dataset, train_ratio=train_ratio)

        predictor_ = predictor.OpenAIPredictor(
            model=self.model, client=self._openai_client, **self.model_kwargs
        )

        def optimization_objective(trial: optuna.Trial) -> float:
            n_examples = trial.suggest_int("n_examples", self.min_examples, self.max_examples)

            example_indices = [
                trial.suggest_categorical(
                    f"example_{i}", list(range(len(train_set)))
                )
                for i in range(n_examples)
            ]

            param = prompt_parameter.PromptParameter(
                name="few_shot_examples",
                instruction=prompt,
                few_shot_examples_inputs_from_dataset_columns_mapping=few_shot_examples_inputs_from_dataset_columns_mapping,
                demos=[
                    train_set[idx]
                    for idx in example_indices
                ],
            )

            score = evaluator.evaluate_predictor(
                dataset=opik_dataset,
                validation_items_ids=[example["id"] for example in validation_set],
                predictor_=predictor_,
                predictor_parameter=param,
                metric=metric,
                project_name=self.project_name,
                num_threads=num_threads,
                metric_inputs_from_dataset_columns_mapping=metric_inputs_from_dataset_columns_mapping,
                metric_inputs_from_predictor_output_mapping=metric_inputs_from_predictor_output_mapping,
            )
            trial.set_user_attr("score", score)

            return score

        study = optuna.create_study(
            direction="maximize"
        )  # if we need to customize sampling, we can pass sampler here

        study.optimize(
            optimization_objective,
            n_trials=n_trials,
        )

        best_trial = study.best_trial
        best_n_examples = best_trial.params["n_examples"]
        best_indices = [
            best_trial.params[f"example_{i}"] for i in range(best_n_examples)
        ]

        best_param = prompt_parameter.PromptParameter(
            name="few_shot_examples",
            instruction=prompt,
            few_shot_examples_inputs_from_dataset_columns_mapping=few_shot_examples_inputs_from_dataset_columns_mapping,
            demos=[train_set[idx] for idx in best_indices],
        )

        return optimization_result.OptimizationResult(
            prompt=best_param.as_prompt_template().text,
            score=best_trial.user_attrs["score"],
            metric_name=metric.name,
            details={
                "prompt_parameter": best_param,
                "n_examples": best_n_examples,
                "trial_number": best_trial.number,
            },
        )

    def evaluate_prompt(
        self,
        dataset: Union[str, Dataset],
        metric: BaseMetric,
        prompt: str,
        metric_inputs_from_dataset_columns_mapping: Dict[str, Any],
        metric_inputs_from_predictor_output_mapping: Dict[str, Any],
        num_test: int = None,
        num_threads: int = 12,
    ) -> float:
        if isinstance(dataset, str):
            dataset = self._opik_client.get_dataset(dataset)

        predictor_ = predictor.OpenAIPredictor(
            model=self.model,
            client=self._openai_client,
            **self.model_kwargs
        )

        param = prompt_parameter.PromptParameter(
            name="few_shot_examples_prompt",
            instruction=prompt,
        )

        score = evaluator.evaluate_predictor(
            dataset=dataset,
            metric=metric,
            validation_items_ids=None,
            predictor_=predictor_,
            predictor_parameter=param,
            metric_inputs_from_dataset_columns_mapping=metric_inputs_from_dataset_columns_mapping,
            metric_inputs_from_predictor_output_mapping=metric_inputs_from_predictor_output_mapping,
            num_threads=num_threads,
            project_name=self.project_name,
            num_test=num_test,
        )

        return score


def _split_dataset(
    dataset: List[Dict[str, Any]], train_ratio: float
) -> Tuple[List[Dict[str, Any]], List[Dict[str, Any]]]:
    shuffled = random.sample(dataset, len(dataset))
    train_set = shuffled[: int(train_ratio * len(dataset))]
    validation_set = shuffled[int(train_ratio * len(dataset)) :]

    return train_set, validation_set
