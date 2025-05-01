from opik.evaluation import metrics
from opik.evaluation.metrics import score_result
from opik_optimizer.few_shot_bayesian_optimizer import FewShotBayesianOptimizer
from opik_optimizer.demo import get_or_create_dataset

from opik_optimizer import (
    OptimizationConfig,
    MetricConfig,
    PromptTaskConfig,
    from_dataset_field,
    from_llm_response_text,
)


class HaluEvalObjective(metrics.BaseMetric):
    def __init__(self, name: str = "halu_eval_objective", track: bool = True):
        super().__init__(name=name, track=track)

    def score(
        self, ground_truth: str, output: str, **ignored_kwargs
    ) -> score_result.ScoreResult:
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


# For chat prompts instruction doesn't need to contain input parameters from dataset examples.
prompt_instruction = """
Detect hallucinations in the given user inputs and llm_output pairs.

Answer with just one word: 'yes' if there is a hallucination and 'no' if there is not.
"""


optimizer = FewShotBayesianOptimizer(
    model="gpt-4o-mini",
    project_name="optimize-few-shot-bayesian-halueval",
    min_examples=2,
    max_examples=8,
    n_threads=8,
    seed=42,
)


optimization_config = OptimizationConfig(
    dataset=halu_eval_dataset,
    objective=MetricConfig(
        metric=halu_eval_accuracy,
        inputs={
            "output": from_llm_response_text(),
            "ground_truth": from_dataset_field(name="expected_hallucination_label"),
        },
    ),
    task=PromptTaskConfig(
        instruction_prompt=prompt_instruction,
        input_dataset_fields=["input", "llm_output"],
        output_dataset_field="expected_hallucination_label",
        use_chat_prompt=True,
    ),
)

initial_prompt_no_examples = [
    {"role": "system", "content": prompt_instruction},
    {"role": "user", "content": "The user input: {{input}} \n\n The llm output: {{llm_output}}"},
]

initial_score = optimizer.evaluate_prompt(
    dataset=halu_eval_dataset,
    metric_config=optimization_config.objective,
    prompt=initial_prompt_no_examples,
)

print("Initial score:", initial_score)

result = optimizer.optimize_prompt(optimization_config, n_trials=10)

print("Final prompt:", result.prompt)

final_score = optimizer.evaluate_prompt(
    dataset=halu_eval_dataset,
    metric_config=optimization_config.objective,
    prompt=result.prompt,
)

print("Final score:", final_score)
