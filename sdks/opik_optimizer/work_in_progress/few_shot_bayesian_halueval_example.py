from opik.evaluation import metrics
from opik.evaluation.metrics import score_result
from opik_optimizer.few_shot_bayesian_optimizer import FewShotBayesianOptimizer
from opik_optimizer.demo import get_or_create_dataset

class HaluEvalObjective(metrics.BaseMetric):
    def __init__(self, name: str = "halu_eval_objective", track: bool = True):
        super().__init__(name=name, track=track)

    def score(self, ground_truth: str, output: str, **ignored_kwargs) -> score_result.ScoreResult:
        ground_truth = ground_truth.lower()
        output = output.lower()

        true_negative_weight = 1.0
        true_positive_weight = 6.0

        # The dataset is imbalanced (<20% hallucinations), higher true-positive weight will make the prompt more sensitive,
        # leading to more hallucinations detected, but also to more false-positive classifications.

        if ground_truth == "no":
            return score_result.ScoreResult(
                value=(1 if ground_truth == output else 0) * true_negative_weight,
                name=self.name,
            )

        return score_result.ScoreResult(
            value=(1 if ground_truth == output else 0) * true_positive_weight,
            name=self.name,
        )

halu_eval_accuracy = HaluEvalObjective()
halu_eval_dataset = get_or_create_dataset("halu-eval-300")

prompt = """
Detect hallucinations in the given user inputs and llm_output pairs.

Answer with just one word: 'yes' if there is a hallucination and 'no' if there is not.

The user input: 
{{input}}

The llm output: 
{{llm_output}}
"""


optimizer = FewShotBayesianOptimizer(
    model="gpt-4o-mini",
    project_name="optimize-few-shot-bayesian-halueval",
    min_examples=2,
    max_examples=8,
)

metric_inputs_from_dataset_columns_mapping = {
    "ground_truth": "expected_hallucination_label",
}
metric_inputs_from_predictor_output_mapping = {
    "output": "evaluated_output"  # evaluated_output is the reserved name for predictor output string
}

initial_score = optimizer.evaluate_prompt(
    dataset=halu_eval_dataset,
    metric=halu_eval_accuracy,
    prompt=prompt,
    metric_inputs_from_dataset_columns_mapping=metric_inputs_from_dataset_columns_mapping,
    metric_inputs_from_predictor_output_mapping=metric_inputs_from_predictor_output_mapping,
    num_threads=16,
)

print("Initial score:", initial_score)

result = optimizer.optimize_prompt(
    dataset=halu_eval_dataset,
    metric=halu_eval_accuracy,
    prompt=prompt,
    few_shot_examples_inputs_from_dataset_columns_mapping={
        "User input": "input",
        "LLM output": "llm_output",
        "Hallucination verdict": "expected_hallucination_label",
    },
    metric_inputs_from_dataset_columns_mapping=metric_inputs_from_dataset_columns_mapping,
    metric_inputs_from_predictor_output_mapping=metric_inputs_from_predictor_output_mapping,
    n_trials=10,
    train_ratio=0.4,
    num_threads=16,
)

print(result.prompt)
final_score = optimizer.evaluate_prompt(
    dataset=halu_eval_dataset,
    metric=halu_eval_accuracy,
    prompt=result.prompt,
    metric_inputs_from_dataset_columns_mapping=metric_inputs_from_dataset_columns_mapping,
    metric_inputs_from_predictor_output_mapping=metric_inputs_from_predictor_output_mapping,
    num_threads=16,
)

print("Final score:", final_score)
