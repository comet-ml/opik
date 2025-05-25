from opik_optimizer import (
    MiproOptimizer,
    TaskConfig,
    MetricConfig,
    from_llm_response_text,
    from_dataset_field
)
from opik.evaluation.metrics import LevenshteinRatio
from opik_optimizer import datasets


def test_mipro_optimizer():
    # Initialize optimizer
    optimizer = MiproOptimizer(
        model="openai/gpt-4o",
        project_name="mipro_optimization_project",
        temperature=0.1,
        max_tokens=5000
    )

    # Prepare dataset
    dataset = datasets.hotpot_300()

    # Define metric and task configuration
    metric_config = MetricConfig(
        metric=LevenshteinRatio(),
        inputs={
            "output": from_llm_response_text(),
            "reference": from_dataset_field(name="answer"),
        }
    )

    # Define some tools
    def calculator(expression):
        """Perform mathematical calculations"""
        return str(eval(expression))

    def search(query):
        """Search for information on a given topic"""
        # placeholder for search functionality
        return "hello_world"

    # Define task configuration with tools
    task_config = TaskConfig(
        instruction_prompt="Complete the task using the provided tools.",
        input_dataset_fields=["question"],
        output_dataset_field="answer",
        use_chat_prompt=True,
        tools=[search, calculator]
    )

    # Run optimization
    results = optimizer.optimize_prompt(
        dataset=dataset,
        metric_config=metric_config,
        task_config=task_config,
        num_candidates=1,
        num_trials=2,
    )

    # Access results
    assert len(results.history) > 0
