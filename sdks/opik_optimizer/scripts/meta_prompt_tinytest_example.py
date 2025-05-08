from opik_optimizer import MetaPromptOptimizer
from opik.evaluation.metrics import LevenshteinRatio
from opik_optimizer.demo import get_or_create_dataset

from opik_optimizer import (
    MetricConfig,
    TaskConfig,
    from_dataset_field,
    from_llm_response_text,
)

# Get or create the test dataset
tiny_test_dataset = get_or_create_dataset("tiny-test")
project_name = "optimize-metaprompt-tinytest"
# Define the initial prompt to optimize - intentionally vague to allow for improvement
initial_prompt = """Answer the question."""

# Initialize the optimizer with custom parameters
optimizer = MetaPromptOptimizer(
    model="openai/o3-mini",  # Using o3-mini for evaluation (reasoning models are slow on metaprompter)
    project_name=project_name,
    max_rounds=1,  # Increased rounds for more optimization
    num_prompts_per_round=4,  # More prompts per round
    improvement_threshold=0.01,  # Lower threshold to allow more improvements
    temperature=0.1,  # Lower temperature for more focused responses
    max_completion_tokens=5000,  # Maximum tokens for model completion
    num_threads=16,  # Number of threads for parallel evaluation
)

# Create the optimization configurations

metric_config = MetricConfig(
    metric=LevenshteinRatio(project_name=project_name),
    inputs={
        "output": from_llm_response_text(),
        "reference": from_dataset_field(name="label"),
    },
)

task_config = TaskConfig(
    instruction_prompt=initial_prompt,
    input_dataset_fields=["text"],
    output_dataset_field="label",
)

result = optimizer.optimize_prompt(
    dataset=tiny_test_dataset,
    metric_config=metric_config,
    task_config=task_config,
    auto_continue=False,
)

result.display()
