from typing import Dict, Any


from opik.evaluation.metrics.score_result import ScoreResult
from opik.evaluation.metrics import LevenshteinRatio

from opik_optimizer import (
    ChatPrompt,
    MetaPromptOptimizer,
)
from opik_optimizer.datasets import hotpot_300
from pydantic_ai_agent import PydanticAIAgent


def levenshtein_ratio(dataset_item: Dict[str, Any], llm_output: str) -> ScoreResult:
    metric = LevenshteinRatio()
    return metric.score(reference=dataset_item["answer"], output=llm_output)


dataset = hotpot_300()

system_prompt = """Use the `search_wikipedia` function to find details
on a topic. Respond with a short, concise answer without
explanation."""


prompt = ChatPrompt(
    system=system_prompt,
    user="{question}",
)

# Optimize it:
optimizer = MetaPromptOptimizer(
    model="openai/gpt-4o-mini",  # Using gpt-4o-mini for evaluation for speed
    max_rounds=3,  # Number of optimization rounds
    num_prompts_per_round=4,  # Number of prompts to generate per round
    improvement_threshold=0.01,  # Minimum improvement required to continue
    temperature=0.1,  # Lower temperature for more focused responses
    max_completion_tokens=5000,  # Maximum tokens for model completion
    n_threads=1,  # Number of threads for parallel evaluation
    subsample_size=10,  # Fixed subsample size of 10 items
)
optimization_result = optimizer.optimize_prompt(
    prompt=prompt,
    agent_class=PydanticAIAgent,
    dataset=dataset,
    metric=levenshtein_ratio,
    n_samples=10,
)
optimization_result.display()
