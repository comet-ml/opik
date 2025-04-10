"""
FewShot algorithm for Opik
"""

from collections import defaultdict
from typing import Any, Dict, List, Tuple, Union, Optional

import dspy
from tqdm import tqdm
from opik import Dataset
from opik.evaluation.metrics import BaseMetric

from .integrations.dspy.utils import create_dspy_signature, State
from .integrations.dspy import DspyOptimizer
from .optimization_result import OptimizationResult


class FewShotOptimizer(DspyOptimizer):
    def optimize_prompt(
        self,
        dataset: Union[str, Dataset],
        metric: BaseMetric,
        prompt: str,
        input_key: str,
        output_key: str,
        num_candidates: int = 10,
        n_trials: int = 10,
    ):
        super().optimize_prompt(
            dataset,
            metric,
            prompt,
            input_key,
            output_key,
            num_candidates,
        )
        # Do steps for Few-shot:
        self.n_trials = n_trials
        # Get the initial state:
        state = self.optimizer.step_0(
            student=self.module,
            trainset=self.trainset,
            provide_traceback=True,
        )
        # Bootstrap the few-shot examples:
        self.optimizer.step_1(state)
        # Propose instruction candidates:
        self.optimizer.step_2(state)

        results = {}
        lm = dspy.settings.lm
        for key, value in state.instruction_candidates.items():
            for i, instruction in enumerate(value):
                if instruction not in results:
                    signature = create_dspy_signature(
                        self.input_key, self.output_key, instruction
                    )
                    program = dspy.Predict(signature)
                    total_score = 0
                    for row in tqdm(self.dataset[: self.n_trials]):
                        output = program(**{self.input_key: row[self.input_key]})
                        score = self.metric_function(
                            State({self.output_key: getattr(output, self.output_key)}),
                            State({self.output_key: row[self.output_key]}),
                        )
                        total_score += score
                    results[instruction] = total_score / self.n_trials

        self.history = [
            sorted(
                [{"prompt": key, "score": value} for key, value in results.items()],
                key=lambda item: item["score"],
                reverse=True,
            )
        ]

        if self.history:
            return OptimizationResult(
                prompt=self.history[0][0]["prompt"],
                score=self.history[0][0]["score"],
                metric_name=self.opik_metric.name,
            )
        else:
            return None
