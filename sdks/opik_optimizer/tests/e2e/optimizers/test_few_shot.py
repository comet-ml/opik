from opik.evaluation.metrics import LevenshteinRatio

from opik_optimizer import (
    FewShotBayesianOptimizer,
    MetricConfig,
    datasets,
    from_dataset_field,
    from_llm_response_text,
)
from opik_optimizer.optimization_config import chat_prompt


def test_few_shot_optimizer():
    # Initialize optimizer
    optimizer = FewShotBayesianOptimizer(
        model="openai/gpt-4",
        temperature=0.1,
        max_tokens=5000,
        n_initial_prompts=2,
        n_iterations=2
    )

    # Prepare dataset
    dataset = datasets.hotpot_300()

    # Define metric and task configuration (see docs for more options)
    metric_config = MetricConfig(
        metric=LevenshteinRatio(),
        inputs={
            "output": from_llm_response_text(),  # Model's output
            "reference": from_dataset_field(name="answer"),  # Ground truth
        }
    )

    prompt = chat_prompt.ChatPrompt(
        system="Provide an answer to the question.",
        prompt="{question}"
    )

    results = optimizer.optimize_prompt(
        dataset=dataset,
        metric_config=metric_config,
        prompt=prompt,
        n_trials=10
    )

    # Access results
    assert len(results.history) > 0

if __name__ == "__main__":
    test_few_shot_optimizer()
