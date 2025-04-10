from opik.evaluation.metrics import LevenshteinRatio
from opik_optimizer import MiproOptimizer
from opik_optimizer.demo import get_or_create_dataset

optimizer = MiproOptimizer(
    model="openai/gpt-4o-mini",  # LiteLLM or OpenAI name
    project_name="optimize-mipro-hotpot",
    temperature=0.1,
    max_tokens=5000,
)

hotpot_dataset = get_or_create_dataset("hotpot-300")

best_prompt = optimizer.optimize_prompt(
    dataset=hotpot_dataset,
    metric=LevenshteinRatio(),
    prompt="Answer the question with a short, 1 to 5 word phrase",
    # Algorithm-specific kwargs:
    input_key="question",
    output_key="answer",
)

print(best_prompt)
