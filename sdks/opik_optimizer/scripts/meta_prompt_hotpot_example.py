from opik_optimizer import MetaPromptOptimizer
from opik.evaluation.metrics import LevenshteinRatio
from opik_optimizer.demo import get_or_create_dataset

from opik_optimizer import (
    MetricConfig,
    TaskConfig,
    from_dataset_field,
    from_llm_response_text,
)

# Get or create the Hotpot dataset
hotpot_dataset = get_or_create_dataset("hotpot-300")

# Define the initial prompt to optimize
initial_prompt = "Answer the question."
project_name = "optimize-metaprompt-hotpot"

# Initialize the optimizer with custom parameters
optimizer = MetaPromptOptimizer(
    model="openai/gpt-4o-mini",  # Using gpt-4o-mini for evaluation for speed
    project_name=project_name,
    max_rounds=3,  # Number of optimization rounds
    num_prompts_per_round=4,  # Number of prompts to generate per round
    improvement_threshold=0.01,  # Minimum improvement required to continue
    temperature=0.1,  # Lower temperature for more focused responses
    max_completion_tokens=5000,  # Maximum tokens for model completion
    num_threads=12,  # Number of threads for parallel evaluation
    subsample_size=10,  # Fixed subsample size of 10 items
)

# Create the optimization configuration

metric_config = MetricConfig(
    metric=LevenshteinRatio(project_name=project_name),
    inputs={
        "output": from_llm_response_text(),
        "reference": from_dataset_field(name="answer"),
    },
)

task_config = TaskConfig(
    instruction_prompt=initial_prompt,
    input_dataset_fields=["question"],
    output_dataset_field="answer",
)

# Optimize the prompt using the optimization config
result = optimizer.optimize_prompt(
    dataset=hotpot_dataset,
    metric_config=metric_config,
    task_config=task_config,
    auto_continue=False,
    n_samples=100,  # Explicitly set to 100 samples
    use_subsample=True,  # Force using subsample for evaluation rounds
)

result.display()
