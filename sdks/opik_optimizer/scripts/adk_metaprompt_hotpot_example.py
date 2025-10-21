from typing import Any
from adk_agent import ADKAgent

from opik_optimizer import (
    ChatPrompt,
    MetaPromptOptimizer,
)
from opik_optimizer.datasets import hotpot_300

from opik.evaluation.metrics import LevenshteinRatio
from opik.evaluation.metrics.score_result import ScoreResult


dataset = hotpot_300()


def levenshtein_ratio(dataset_item: dict[str, Any], llm_output: str) -> ScoreResult:
    metric = LevenshteinRatio()
    return metric.score(reference=dataset_item["answer"], output=llm_output)


system_prompt = """
You are a helpful assistant. Use the `search_wikipedia` tool to find factual information when appropriate.
The user will provide a question string like "Who is Barack Obama?".
1. Extract the item to look up
2. Use the `search_wikipedia` tool to find details
3. Respond clearly to the user, stating the answer found by the tool.
"""

prompt = ChatPrompt(system=system_prompt, user="{question}")

# Optimize it:
optimizer = MetaPromptOptimizer(
    model="openai/gpt-4o-mini",  # Using gpt-4o-mini for evaluation for speed
    prompts_per_round=4,  # Number of prompts to generate per round
    n_threads=12,  # Number of threads for parallel evaluation
    model_parameters={
        "temperature": 0.1,  # Lower temperature for more focused responses
        "max_completion_tokens": 5000,  # Maximum tokens for model completion
    },
)
optimization_result = optimizer.optimize_prompt(
    prompt=prompt,
    agent_class=ADKAgent,
    dataset=dataset,
    metric=levenshtein_ratio,
    max_trials=12,  # Number of total trials (max_rounds * num_prompts_per_round)
    n_samples=10,
)
optimization_result.display()
