import random
from typing import Any, Dict, List, Tuple, Union

import openai
import opik
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
        project_name: str = None,
        max_examples: int = 5,
        seed: int = 42,
        num_threads: int = 4,
        **model_kwargs
    ):
        super().__init__(model, project_name, **model_kwargs)
        self.max_examples = max_examples
        self.seed = seed
        self.num_threads = num_threads

        self._openai_client = track_openai(openai.OpenAI(), project_name=self.project_name)
        self._opik_client = opik.Opik()


    def optimize_prompt(
        self,
        dataset: Union[str, Dataset],
        metric: BaseMetric,
        prompt: str,  # TODO: should we enforce prompt parameter for demos?
        input_key: str,
        output_key: str,
        n_trials: int = 10,
        num_threads: int = 4,
        scoring_key_mapping: Dict[str, str] = None,
        train_ratio: float = 0.2,
        **kwargs
    ) -> prompt_parameter.PromptParameter:
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
            model=self.model,
            client=self._openai_client,
            **self.model_kwargs
        )

        all_train_examples = [
            {input_key: example[input_key], output_key: example[output_key]}
            for example in train_set
        ]
        
        def optimization_objective(trial: optuna.Trial) -> float:
            n_examples = trial.suggest_int("n_examples", 1, self.max_examples)
            
            example_indices = [
                trial.suggest_categorical(f"example_{i}", list(range(len(all_train_examples))))
                for i in range(n_examples)
            ]
            
            param = prompt_parameter.PromptParameter(
                name="few_shot_examples",
                instruction=prompt,
                demos=[all_train_examples[idx] for idx in example_indices]
            )
            
            score = evaluator.evaluate_predictor(
                dataset=opik_dataset,
                validation_items_ids=[example["id"] for example in validation_set],
                predictor_=predictor_,
                predictor_parameter=param,
                metric=metric,
                project_name=self.project_name,
                num_threads=num_threads,
                scoring_key_mapping=scoring_key_mapping,
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
        best_indices = [best_trial.params[f"example_{i}"] for i in range(best_n_examples)]
        
        best_param = prompt_parameter.PromptParameter(
            name="few_shot_examples",
            instruction=prompt,
            demos=[all_train_examples[idx] for idx in best_indices]
        )
        
        return {
            "prompt": best_param.as_prompt_template().text,
            "prompt_parameter": best_param,
            "metric_score": best_trial.user_attrs["score"]
        }


def _split_dataset(dataset: List[Dict[str, Any]], train_ratio: float) -> Tuple[List[Dict[str, Any]], List[Dict[str, Any]]]:
    shuffled = random.sample(dataset, len(dataset))
    train_set = shuffled[:int(train_ratio * len(dataset))]
    validation_set = shuffled[int(train_ratio * len(dataset)):]
    
    return train_set, validation_set


