from opik_optimizer import EvolutionaryOptimizer
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
project_name = "optimize-evolutionary-hotpot"

# Initialize the optimizer with custom parameters
optimizer = EvolutionaryOptimizer(
    model="gpt-4o-mini",
    project_name=project_name,
    population_size=10,
    num_generations=3,
    enable_moo=False,
    enable_llm_crossover=True,
    infer_output_style=True,
    verbose=1,
)

# Create the optimization configuration

metric_config = MetricConfig(
    metric=LevenshteinRatio(project_name=project_name),
    inputs={
        "output": from_llm_response_text(),
        "reference": from_dataset_field(name="answer"),
    },
)

# Task Configuration
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
    n_samples=10,
)

result.display()
