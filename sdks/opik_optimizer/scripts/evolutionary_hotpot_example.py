from opik.evaluation.metrics import LevenshteinRatio

from opik_optimizer import ChatPrompt, EvolutionaryOptimizer
from opik_optimizer.datasets import hotpot_300

# Get or create the Hotpot dataset
hotpot_dataset = hotpot_300()

# Define the initial prompt to optimize
prompt = ChatPrompt(
    messages=[
        {"role": "system", "content": "Answer the question."},
        {"role": "user", "content": "{question}"},
    ]
)
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
def levenshtein_ratio(dataset_item, llm_output):
    metric = LevenshteinRatio()
    return metric.score(reference=dataset_item["answer"], output=llm_output)

# Optimize the prompt using the optimization config
result = optimizer.optimize_prompt(
    prompt=prompt,
    dataset=hotpot_dataset,
    metric=levenshtein_ratio,
    n_samples=10,
)

result.display()
