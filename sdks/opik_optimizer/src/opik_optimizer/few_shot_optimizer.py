"""
FewShot algorithm for Opik
"""
from collections import defaultdict

import dspy
from tqdm import tqdm
from opik.api_objects.dataset.dataset import Dataset
from opik.evaluation.metrics import BaseMetric

from .integrations.dspy.utils import create_dspy_signature, State
from .integrations.dspy import DspyOptimizer

class FewShotOptimizer(DspyOptimizer):
    def optimize_prompt(
        self,
        dataset: str | Dataset,
        metric: BaseMetric,
        prompt: str,
        input: str,
        output: str,
        num_candidates: int = 10,
        num_test: int = 30,
    ):
        super().optimize_prompt(
            dataset,
            metric,
            prompt,
            input,
            output,
            num_candidates,
        )
        # Do steps for Few-shot:
        self.num_test = num_test
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
                        self.input, self.output, instruction
                    )
                    program = dspy.Predict(signature)
                    total_score = 0
                    for row in tqdm(self.dataset[: self.num_test]):
                        output = program(**{self.input: row[self.input]})
                        score = self.metric_function(
                            State({self.output: getattr(output, self.output)}),
                            State({self.output: row[self.output]}),
                        )
                        total_score += score
                    results[instruction] = total_score / self.num_test

        self.history = [
            sorted(
                [{"prompt": key, "score": value} for key, value in results.items()],
                key=lambda item: item["score"],
                reverse=True,
            )
        ]

        if self.history:
            return self.history[0][0]["prompt"]
        else:
            return None
