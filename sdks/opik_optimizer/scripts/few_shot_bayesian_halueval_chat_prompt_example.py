from opik.evaluation import metrics
from opik.evaluation.metrics import score_result
from opik_optimizer import FewShotBayesianOptimizer
from opik_optimizer.demo import get_or_create_dataset

from opik_optimizer import (
    MetricConfig,
    TaskConfig,
    from_dataset_field,
    from_llm_response_text,
)


class HaluEvalObjective(metrics.BaseMetric):
    def __init__(self, name: str = "halu_eval_objective", track: bool = True, **kwargs):
        super().__init__(name=name, track=track, **kwargs)

    def score(
        self, ground_truth: str, output: str, **ignored_kwargs
    ) -> score_result.ScoreResult:
        ground_truth = ground_truth.lower()
        output = output.lower()

        true_negative_weight = 1.0
        true_positive_weight = 6.0

        # The dataset is imbalanced, higher true-positive weight will make the prompt more sensitive,
        # leading to more hallucinations detected, but also to more false-positive classifications.

        if ground_truth == "no":
            return score_result.ScoreResult(
                value=(1 if ground_truth == output else 0) * true_negative_weight,
                name=self.name,
            )
        elif ground_truth == "yes":
            return score_result.ScoreResult(
                value=(1 if ground_truth == output else 0) * true_positive_weight,
                name=self.name,
            )
        else:
            raise ValueError(f"Invalid ground truth value: {ground_truth}")


project_name = "optimize-few-shot-bayesian-halueval"
halu_eval_accuracy = HaluEvalObjective(project_name=project_name)
halu_eval_dataset = get_or_create_dataset("halu-eval-300")


# For chat prompts instruction doesn't need to contain input parameters from dataset examples.
prompt_instruction = """
Detect hallucinations in the given user inputs and llm_output pairs.

Answer with just one word: 'yes' if there is a hallucination and 'no' if there is not.
"""

optimizer = FewShotBayesianOptimizer(
    model="gpt-4o-mini",
    project_name=project_name,
    min_examples=4,
    max_examples=8,
    n_threads=6,
    seed=42,
)

metric_config = MetricConfig(
    metric=halu_eval_accuracy,
    inputs={
        "output": from_llm_response_text(),
        "ground_truth": from_dataset_field(name="expected_hallucination_label"),
    },
)

task_config = TaskConfig(
    instruction_prompt=prompt_instruction,
    input_dataset_fields=["input", "llm_output"],
    output_dataset_field="expected_hallucination_label",
    use_chat_prompt=True,
)

result = optimizer.optimize_prompt(
    dataset=halu_eval_dataset,
    metric_config=metric_config,
    task_config=task_config,
    n_trials=10,
    n_samples=200,
)

result.display()
