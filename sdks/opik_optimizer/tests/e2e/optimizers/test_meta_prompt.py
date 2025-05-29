from opik_optimizer import MetaPromptOptimizer
from opik.evaluation.metrics import LevenshteinRatio
from opik_optimizer import (
    MetricConfig,
    TaskConfig,
    from_llm_response_text,
    from_dataset_field,
)
from opik_optimizer import datasets


def test_metaprompt_optimizer():
    # Initialize optimizer
    optimizer = MetaPromptOptimizer(
        model="openai/gpt-4",  # or "azure/gpt-4"
        temperature=0.1,
        max_tokens=5000,
        num_threads=8,
        max_rounds=2,
        num_prompts_per_round=1,
        seed=42
    )

    # Prepare dataset
    dataset = datasets.tiny_test()
    print(dataset.get_items(1))

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

    # Run optimization
    results = optimizer.optimize_prompt(
        dataset=dataset,
        metric_config=metric_config,
        task_config=task_config,
        n_samples=1
    )

    # Access results
    assert len(results.details['rounds']) > 0
