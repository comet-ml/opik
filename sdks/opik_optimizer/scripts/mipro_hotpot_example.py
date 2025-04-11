from opik_optimizer.demo import get_or_create_dataset
from opik.evaluation.metrics import LevenshteinRatio
from opik_optimizer import MiproOptimizer

opik_dataset = get_or_create_dataset("hotpot-300")

optimizer = MiproOptimizer(
    model="openai/gpt-4o-mini",  # LiteLLM or OpenAI name
    project_name="optimize-mipro-hotpot",
    temperature=0.1,
    max_tokens=5000,
)

initial_prompt = "Answer the question with a short, 1 to 5 word phrase"

score = optimizer.evaluate_prompt(
    dataset=opik_dataset,
    metric=LevenshteinRatio(),
    prompt=initial_prompt,
    # Algorithm-specific kwargs:
    input_key="question",
    output_key="answer",
)

print("Initial prompt:", initial_prompt)
print("Score:", score)

results = optimizer.optimize_prompt(
    dataset=opik_dataset,
    metric=LevenshteinRatio(),
    prompt=initial_prompt,
    # Algorithm-specific kwargs:
    input_key="question",
    output_key="answer",
)

print(results)
