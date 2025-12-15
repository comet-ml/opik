from typing import Any

from opik_optimizer import (
    ChatPrompt,
    HRPO,
)
from opik_optimizer.datasets import hotpot

from opik.evaluation.metrics import LevenshteinRatio
from opik.evaluation.metrics.score_result import ScoreResult

from adk_agent import ADKAgent

dataset = hotpot(count=300)


def levenshtein_ratio(dataset_item: dict[str, Any], llm_output: str) -> ScoreResult:
    metric = LevenshteinRatio()
    result = metric.score(reference=dataset_item["answer"], output=llm_output)
    # Add reason field required by HRPO
    result.reason = f"Levenshtein similarity between output and reference '{dataset_item['answer']}': {result.value:.4f}"
    return result


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
optimizer = HRPO(
    model="openai/gpt-4o-mini",
    n_threads=1,
    max_parallel_batches=3,
    model_parameters={"temperature": 0.7, "max_tokens": 4096},
    seed=42,
    verbose=1,
)
optimization_result = optimizer.optimize_prompt(
    prompt=prompt,
    agent_class=ADKAgent,
    dataset=dataset,
    metric=levenshtein_ratio,
    max_trials=5,
    n_samples=10,
    max_retries=0,
)

optimization_result.display()
