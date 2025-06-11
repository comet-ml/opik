from opik.evaluation.metrics import LevenshteinRatio

from opik_optimizer import ChatPrompt, MetaPromptOptimizer
from opik_optimizer.datasets import tiny_test

# Get or create the test dataset
tiny_test_dataset = tiny_test()
project_name = "optimize-metaprompt-tinytest"

# Define the initial prompt to optimize - intentionally vague to allow for improvement
initial_prompt = ChatPrompt(
    messages=[
        {"role": "system", "content": "Answer the question."},
        {"role": "user", "content": "{text}"}
    ]
)

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
def levenshtein_ratio(dataset_item, llm_output):
    metric = LevenshteinRatio()
    return metric.score(reference=dataset_item["label"], output=llm_output)

result = optimizer.optimize_prompt(
    prompt=initial_prompt,
    dataset=tiny_test_dataset,
    metric=levenshtein_ratio,
    auto_continue=False,
)

result.display()
