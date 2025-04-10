from opik_optimizer import MetaPromptOptimizer
from opik.evaluation.metrics import LevenshteinRatio
import os
from opik_optimizer.utils import TEST_DATASET_NAME, get_or_create_dataset
from opik_optimizer.datasets.test_data import TEST_DATA

# Get or create the test dataset
opik_dataset = get_or_create_dataset(
    dataset_name=TEST_DATASET_NAME,
    description="Tiny test dataset for prompt optimization",
    data=TEST_DATA
)

# Initialize the optimizer with custom parameters
optimizer = MetaPromptOptimizer(
    model="o3-mini",  # Using o3-mini for evaluation
    reasoning_model="o3-mini",  # Using o3-mini for prompt generation
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
    input="text",
    output="label",
) 