from typing import Any

from opik_optimizer import (
    ChatPrompt,
    FewShotBayesianOptimizer,
)
from opik_optimizer.datasets import hotpot

from opik.evaluation.metrics import LevenshteinRatio
from opik.evaluation.metrics.score_result import ScoreResult

from adk_agent import ADKAgent

dataset = hotpot(count=300)


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

prompt = ChatPrompt(
    system=system_prompt,
    user="{question}",
)

# Optimize it:
optimizer = FewShotBayesianOptimizer(
    model="openai/gpt-4o-mini",
    min_examples=3,
    max_examples=8,
    n_threads=1,
    verbose=1,
    seed=42,
)
optimization_result = optimizer.optimize_prompt(
    prompt=prompt,
    agent_class=ADKAgent,
    dataset=dataset,
    metric=levenshtein_ratio,
    n_samples=10,
    max_trials=5,
)

optimization_result.display()
