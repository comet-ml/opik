"""
MIPRO algorithm for Opik
"""

from typing import Any, Dict, List, Tuple, Union, Optional

from .integrations.dspy import DspyOptimizer
from opik import Dataset
from opik.evaluation.metrics import BaseMetric


class MiproOptimizer(DspyOptimizer):
    def optimize_prompt(
        self,
        dataset: Union[str, Dataset],
        metric: BaseMetric,
        prompt: str,
        input_key: str,
        output_key: str,
        num_candidates: int = 10,
    ):
        super().optimize_prompt(
            dataset,
            metric,
            prompt,
            input_key,
            output_key,
            num_candidates,
        )
        # Do steps for MIPRO:
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
        # # Step 3: Find optimal prompt parameters
        self.results = self.optimizer.step_3(state)
        # FIXME: add to history
        self.best_programs = sorted(
            self.results.candidate_programs,
            key=lambda item: item["score"],
            reverse=True,
        )
        self.state = state
        return self.best_programs[0]["program"].signature.instructions
