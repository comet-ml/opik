from opik.evaluation.metrics import LevenshteinRatio

from opik_optimizer import ChatPrompt, MetaPromptOptimizer
from opik_optimizer.datasets import hotpot_300

# Get or create the Hotpot dataset
hotpot_dataset = hotpot_300()

# Define the initial prompt to optimize
initial_prompt = ChatPrompt(
    messages=[
        {"role": "system", "content": "Answer the question."},
        {"role": "user", "content": "{question}"},
    ]
)
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
def levenshtein_ratio(dataset_item, llm_output):
    metric = LevenshteinRatio()
    return metric.score(reference=dataset_item["answer"], output=llm_output)

# Optimize the prompt using the optimization config
result = optimizer.optimize_prompt(
    prompt=initial_prompt,
    dataset=hotpot_dataset,
    metric=levenshtein_ratio,
    auto_continue=False,
    n_samples=100,  # Explicitly set to 100 samples
    use_subsample=True,  # Force using subsample for evaluation rounds
)

result.display()
