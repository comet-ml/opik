from opik_optimizer.demo import get_or_create_dataset

from opik.evaluation.metrics import LevenshteinRatio
from opik_optimizer.few_shot_optimizer import FewShotOptimizer

optimizer = FewShotOptimizer(
    model="openai/gpt-4o-mini",  # LiteLLM or OpenAI name
    project_name="optimize-few-shot-hotpot",
    temperature=0.1,
    max_tokens=5000,
)

opik_dataset = get_or_create_dataset("hotpot-300")

results = optimizer.optimize_prompt(
    dataset=opik_dataset,
    metric=LevenshteinRatio(),
    prompt="Answer the question with a short, 1 to 5 word phrase",
    # Algorithm-specific kwargs:
    input_key="question",
    output_key="answer",
)

print(results)
