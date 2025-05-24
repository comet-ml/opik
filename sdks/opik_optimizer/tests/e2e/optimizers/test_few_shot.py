from opik_optimizer import FewShotBayesianOptimizer
from opik.evaluation.metrics import LevenshteinRatio
from opik_optimizer import (
    MetricConfig,
    TaskConfig,
    from_llm_response_text,
    from_dataset_field,
)
from opik_optimizer import datasets


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
    dataset = datasets.tiny_test()

    # Define metric and task configuration (see docs for more options)
    metric_config = MetricConfig(
        metric=LevenshteinRatio(),
        inputs={
            "output": from_llm_response_text(),  # Model's output
            "reference": from_dataset_field(name="label"),  # Ground truth
        }
    )
    task_config = TaskConfig(
        instruction_prompt="Provide an answer to the question.",
        input_dataset_fields=["text"],
        output_dataset_field="label",
        use_chat_prompt=True
    )

    results = optimizer.optimize_prompt(
        dataset=dataset,
        metric_config=metric_config,
        task_config=task_config,
        n_trials=2
    )

    # Access results
    assert len(results.history) > 0
