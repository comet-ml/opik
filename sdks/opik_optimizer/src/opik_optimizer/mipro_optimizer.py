"""
MIPRO algorithm for Opik
"""

from .integrations.dspy import DspyOptimizer
from dspy.datasets.dataset import Dataset
from opik.evaluation.metrics import BaseMetric

class MiproOptimizer(DspyOptimizer):
    def optimize_prompt(
            self,
            dataset: str | Dataset,
            metric: BaseMetric,
            prompt: str,
            input: str,
            output: str,
            num_candidates: int = 10,
    ):
        super().optimize_prompt(
            dataset,
            metric,
            prompt,
            input,
            output,
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
        print("Demonstration candidates:")
        for key, value in state.demo_candidates.items():
            print("    ", "Group:", key + 1, "number of examples:", len(value[1]))
        # Propose instruction candidates:
        self.optimizer.step_2(state)
        print("Instruction candidates:")
        for key, value in state.instruction_candidates.items():
            print("    Group:", key + 1)
            for i, instruction in enumerate(value):
                print("        ", i + 1, ":", instruction)
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

