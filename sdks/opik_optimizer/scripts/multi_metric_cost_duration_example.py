"""Small multi-objective optimization example for Opik Optimizer.

This example runs a tiny CNN/DailyMail slice and optimizes a single objective that
combines:
- accuracy: Levenshtein similarity on summary text
- latency proxy: lower duration is better
- cost proxy: lower optimizer-reported LLM cost is better

"""

from opik_optimizer import ChatPrompt, HRPO
from opik_optimizer import MultiMetricObjective
from opik_optimizer.datasets import cnn_dailymail
from opik_optimizer.metrics import (
    LevenshteinAccuracyMetric,
    TotalSpanCost,
    SpanDuration,
)


# Keep the run small for quick experimentation.
N_SAMPLES = 2
MAX_TRIALS = 4
TARGET_DURATION_SECONDS = 6.0
TARGET_COST_USD = 0.01
def make_multi_metric_objective() -> MultiMetricObjective:
    accuracy_metric = LevenshteinAccuracyMetric(
        reference_key="highlights",
        output_key="output",
        name="accuracy",
    )
    cost_metric = TotalSpanCost(target=TARGET_COST_USD, name="cost")
    duration_metric = SpanDuration(
        target=TARGET_DURATION_SECONDS,
        name="duration",
    )

    return MultiMetricObjective(
        metrics=[accuracy_metric, cost_metric, duration_metric],
        weights=[0.5, 0.25, 0.25],
        name="accuracy_cost_duration",
    )


# Small dataset samples for a fast optimization run.
train_dataset = cnn_dailymail(
    split="train",
    count=N_SAMPLES,
    test_mode=True,
)
validation_dataset = cnn_dailymail(
    split="validation",
    count=N_SAMPLES,
    test_mode=True,
)

prompt = ChatPrompt(
    system="Summarize the article clearly in 2-4 concise sentences.",
    user="Article: {article}",
)

optimizer = HRPO(
    model="openai/gpt-5-nano",
    model_parameters={
        "temperature": 1.0,
        "max_completion_tokens": 20000,
    },
)

multi_metric_objective = make_multi_metric_objective()

def run_example() -> None:
    result = optimizer.optimize_prompt(
        prompt=prompt,
        dataset=train_dataset,
        validation_dataset=validation_dataset,
        metric=multi_metric_objective,
        n_samples=N_SAMPLES,
        max_trials=MAX_TRIALS,
        n_samples_strategy="random_sorted",
    )

    result.display()


if __name__ == "__main__":
    run_example()
