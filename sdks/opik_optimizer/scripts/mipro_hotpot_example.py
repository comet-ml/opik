from opik_optimizer import MiproOptimizer
from opik.evaluation.metrics import LevenshteinRatio
import os

optimizer = MiproOptimizer(
    model="openai/gpt-4o-mini",  # LiteLLM name
    api_key=os.environ["OPENAI_API_KEY"],
    temperature=0.0,
    max_tokens=5000,
)

best_prompt = optimizer.optimize_prompt(
    dataset="hotpot-300",
    metric=LevenshteinRatio(),
    prompt="Answer the question with a short, 1 to 5 word phrase",
    # kwargs:
    input="question",
    output="answer",
)

print(best_prompt)
