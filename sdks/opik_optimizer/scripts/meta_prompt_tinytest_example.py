import os

from opik_optimizer import MetaPromptOptimizer
from opik.evaluation.metrics import LevenshteinRatio
from opik_optimizer.demo import get_or_create_dataset

# Get or create the test dataset
opik_dataset = get_or_create_dataset("tiny-test")

# Initialize the optimizer with custom parameters
optimizer = MetaPromptOptimizer(
    model="o3-mini",  # Using o3-mini for evaluation
    project_name="optimize-metaprompt-tinytest",
    max_rounds=3,  # Increased rounds for more optimization
    num_prompts_per_round=4,  # More prompts per round
    improvement_threshold=0.01,  # Lower threshold to allow more improvements
)

# Initial prompt to optimize - intentionally vague to allow for improvement
initial_prompt = """Answer the question."""

# Optimize the prompt
result = optimizer.optimize_prompt(
    dataset=opik_dataset,
    metric=LevenshteinRatio(),
    prompt=initial_prompt,
    input_key="text",
    output_key="label",
)
print(result)