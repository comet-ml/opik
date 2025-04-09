from opik_optimizer.few_shot_optimizer import FewShotOptimizer
from opik.evaluation.metrics import LevenshteinRatio
import os

optimizer = FewShotOptimizer(
    model="openai/gpt-4o-mini",  # LiteLLM name
    api_key=os.environ["OPENAI_API_KEY"],
    temperature=0.0,
    max_tokens=5000,
)

results = optimizer.optimize_prompt(
    dataset="hotpot-300",
    metric=LevenshteinRatio(),
    prompt="Answer the question with a short, 1 to 5 word phrase",
    # kwargs:
    input="question",
    output="answer",
)

print(results)
