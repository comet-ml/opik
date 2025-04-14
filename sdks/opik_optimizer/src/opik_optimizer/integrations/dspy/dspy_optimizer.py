from typing import Any, Dict, List, Tuple, Union, Optional

import opik
from opik.integrations.dspy.callback import OpikCallback
from opik.evaluation.metrics import BaseMetric
from opik import Dataset

import dspy
from dspy.clients.base_lm import BaseLM

from tqdm import tqdm

from ...base_optimizer import BaseOptimizer
from .mipro_optimizer_v2 import MIPROv2
from .utils import (
    State,
    create_dspy_signature,
    opik_metric_to_dspy,
    create_dspy_training_set,
)


class DspyOptimizer(BaseOptimizer):
    def __init__(self, model, **model_kwargs):
        super().__init__(model, **model_kwargs)
        self.strategy = "Predict"
        self.tools = []
        self.num_threads = 6
        self.model_kwargs["model"] = self.model
        lm = dspy.LM(**self.model_kwargs)
        opik_callback = OpikCallback(project_name=self.project_name)
        dspy.configure(lm=lm, callbacks=[opik_callback])

    def optimize_prompt(
        self,
        dataset: Union[str, Dataset],
        metric: BaseMetric,
        prompt: str,
        input_key: str,
        output_key: str,
        num_candidates: int = 10,
    ):
        self.num_candidates = num_candidates
        self.seed = 9
        self.input_key = input_key
        self.output_key = output_key
        self.prompt = prompt

        # Convert to values for MIPRO:
        if isinstance(dataset, str):
            opik_client = opik.Opik(project_name=self.project_name)
            self.dataset = opik_client.get_dataset(dataset).get_items()
        else:
            self.dataset = dataset.get_items()

        # Validate dataset:
        for row in self.dataset:
            if self.input_key not in row:
                raise Exception("row does not contain input_key: %r" % self.input_key)
            if self.output_key not in row:
                raise Exception("row does not contain output_key: %r" % self.output_key)

        self.trainset = create_dspy_training_set(self.dataset, self.input_key)
        self.data_signature = create_dspy_signature(
            self.input_key, self.output_key, self.prompt
        )

        if self.tools:
            if self.strategy not in ["", None, "ReAct"]:
                print("Cannot use tools with %r; using 'ReAct'" % self.strategy)
            self.module = dspy.ReAct(self.data_signature, tools=self.tools)
        else:
            self.module = getattr(dspy, self.strategy)(self.data_signature)

        self.metric_function = opik_metric_to_dspy(metric, self.output_key)
        self.opik_metric = metric

        # Initialize the optimizer:
        self.optimizer = MIPROv2(
            metric=self.metric_function,
            auto="light",
            num_threads=self.num_threads,
            verbose=False,
            num_candidates=self.num_candidates,
            seed=self.seed,
        )

    def evaluate_prompt(
        self,
        dataset: Union[str, Dataset],
        metric: BaseMetric,
        prompt: str,
        input_key: str,
        output_key: str,
        num_test: int = 10,
    ) -> float:
        """
        Compute the score of a prompt on dataset (or part thereof)

        Args:
            dataset: Opik dataset name or dataset
            metric: Instance of an Opik metric
            prompt: The prompt to evaluate
            input_key: input field of dataset
            output_key: output field of dataset
            num_test: number of items to test in the dataset

        Returns:
            Evaluation score
        """
        if isinstance(dataset, str):
            opik_client = opik.Opik(project_name=self.project_name)
            dataset = opik_client.get_dataset(dataset).get_items()
        else:
            dataset = dataset.get_items()
        metric_function = opik_metric_to_dspy(metric, output_key)
        total_score = 0
        signature = create_dspy_signature(input_key, output_key, prompt)
        program = dspy.Predict(signature)
        lm = dspy.settings.lm
        total_score = 0
        for row in tqdm(dataset[:num_test]):
            result = program(**{input_key: row[input_key]})
            score = metric_function(
                State({output_key: getattr(result, output_key)}),
                State({output_key: row[output_key]}),
            )
            total_score += score
        return total_score / num_test
