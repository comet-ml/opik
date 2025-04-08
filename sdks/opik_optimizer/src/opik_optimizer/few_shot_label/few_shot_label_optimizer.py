"""
Few Shot Label Optimizer for Opik

This module provides functionality to optimize few-shot examples for improving
prompt performance by selecting the most effective examples from a dataset.
"""

import random
from typing import Any, Dict, List, Tuple, Union

import openai
import opik
import tqdm
from opik import Dataset
from opik.evaluation.metrics import BaseMetric, score_result
from opik.integrations.openai import track_openai

from ..base_optimizer import BaseOptimizer
from . import predictor, prompt_parameter


class FewShotLabelOptimizer(BaseOptimizer):
    
    def __init__(
        self,
        model: str,
        project_name: str = None,
        num_candidates: int = 5,
        max_examples: int = 5,
        seed: int = 42,
        num_threads: int = 4,
        **model_kwargs
    ):
        super().__init__(model, project_name, **model_kwargs)
        self.num_candidates = num_candidates
        self.max_examples = max_examples
        self.seed = seed
        self.num_threads = num_threads

        self._openai_client = track_openai(openai.OpenAI(), project_name=self.project_name)
        self._opik_client = opik.Opik()


    def optimize_prompt(
        self,
        dataset: Union[str, Dataset],
        metric: BaseMetric,
        prompt: str,
        input_key: str,
        output_key: str,
        **kwargs
    ) -> prompt_parameter.PromptParameter:
        random.seed(self.seed)
        
        # Load the dataset
        if isinstance(dataset, str):
            dataset = self._opik_client.get_dataset(dataset).get_items()
        else:
            dataset = dataset.get_items() if hasattr(dataset, 'get_items') else dataset

        dataset = dataset[:50]
        initial_param = prompt_parameter.PromptParameter(
            name="few_shot_examples",
            instruction=prompt,
            demos=[]
        )
        
        train_set, validation_set = _split_dataset(dataset, train_ratio=0.8)
        
        sampled_candidates = random.sample(train_set, self.num_candidates)
        predictor_ = predictor.OpenAIPredictor(
            model=self.model,
            client=self._openai_client,
            **self.model_kwargs
        )

        best_score = 0
        best_param = initial_param

        for i in range(self.max_examples):
            param_candidates: List[prompt_parameter.PromptParameter] = []
            
            for sampled_candidate in sampled_candidates:
                demo = {input_key: sampled_candidate[input_key], output_key: sampled_candidate[output_key]}
                new_param = best_param.model_copy(deep=True)
                new_param.demos.append(demo)
                param_candidates.append(new_param)

            iteration_results: Dict[float, prompt_parameter.PromptParameter] = {}

            for param_candidate in tqdm.tqdm(param_candidates):
                score = evaluate_predictor(
                    dataset=validation_set,
                    predictor_=predictor_,
                    predictor_parameter=param_candidate,
                    metric=metric,
                )
                if score >= best_score:
                    best_score = score
                    best_param = param_candidate
                
                iteration_results[score] = param_candidate.as_prompt_template()
            
            if max(iteration_results.keys()) < best_score:
                break

        return best_param


def _split_dataset(dataset: List[Dict[str, Any]], train_ratio: float) -> Tuple[List[Dict[str, Any]], List[Dict[str, Any]]]:
    shuffled = random.sample(dataset, len(dataset))
    train_set = shuffled[:int(train_ratio * len(dataset))]
    validation_set = shuffled[int(train_ratio * len(dataset)):]
    
    return train_set, validation_set


@opik.track(project_name="few-shot-optimizers")
def evaluate_predictor(
    dataset: List[Dict[str, Any]],
    predictor_: predictor.OpenAIPredictor,
    predictor_parameter: prompt_parameter.PromptParameter,
    metric: BaseMetric
) -> float:
    score_results: List[score_result.ScoreResult] = []

    for item in tqdm.tqdm(dataset):
        output = predictor_.predict(
            prompt_variables=item,
            prompt_parameter=predictor_parameter,
        )
        score_result_ = metric.score(input=item["question"], output=output, context=[item["answer"]])
        score_results.append(score_result_)
    
    return sum([score_result_.value for score_result_ in score_results]) / len(score_results)