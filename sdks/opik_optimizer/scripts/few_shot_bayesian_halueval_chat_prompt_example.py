from opik.evaluation import metrics
from opik.evaluation.metrics import score_result

from opik_optimizer import ChatPrompt, FewShotBayesianOptimizer
from opik_optimizer.datasets import halu_eval_300


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
halu_eval_dataset = halu_eval_300()


# For chat prompts instruction doesn't need to contain input parameters from dataset examples.
initial_system_prompt = """
Detect hallucinations in the given user inputs and llm_output pairs.

Answer with just one word: 'yes' if there is a hallucination and 'no' if there is not.
"""

prompt = ChatPrompt(
    messages=[
        {"role": "system", "content": initial_system_prompt},
        {"role": "user", "content": "{input}"},
    ]
)

optimizer = FewShotBayesianOptimizer(
    model="gpt-4o-mini",
    project_name=project_name,
    min_examples=4,
    max_examples=8,
    n_threads=6,
    seed=42,
)

def halu_eval_accuracy(dataset_item, llm_output):
    metric = HaluEvalObjective()
    return metric.score(ground_truth=dataset_item["expected_hallucination_label"], output=llm_output)

result = optimizer.optimize_prompt(
    prompt=prompt,
    dataset=halu_eval_dataset,
    metric=halu_eval_accuracy,
    n_trials=10,
    n_samples=200,
)

result.display()
