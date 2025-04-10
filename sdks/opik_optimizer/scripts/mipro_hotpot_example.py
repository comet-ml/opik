# Run the dataset generation script first. In this folder:
# from hotpot_dataset_generation import make_hotpot_qa
# make_hotpot_qa()

from opik.evaluation.metrics import LevenshteinRatio
from opik_optimizer import MiproOptimizer

optimizer = MiproOptimizer(
    model="openai/gpt-4o-mini",  # LiteLLM or OpenAI name
    project_name="optimize-mipro-hotpot",
    temperature=0.1,
    max_tokens=5000,
)

best_prompt = optimizer.optimize_prompt(
    dataset="hotpot-300",
    metric=LevenshteinRatio(),
    prompt="Answer the question with a short, 1 to 5 word phrase",
    # Algorithm-specific kwargs:
    input_key="question",
    output_key="answer",
)

print(best_prompt)
