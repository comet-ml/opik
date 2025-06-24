from typing import Any, Dict

from opik_optimizer import (
    ChatPrompt,
    EvolutionaryOptimizer,
)
from opik_optimizer.datasets import hotpot_300

from opik.evaluation.metrics import LevenshteinRatio
from opik.evaluation.metrics.score_result import ScoreResult

from adk_agent import ADKAgent

dataset = hotpot_300()


def levenshtein_ratio(dataset_item: Dict[str, Any], llm_output: str) -> ScoreResult:
    metric = LevenshteinRatio()
    return metric.score(reference=dataset_item["answer"], output=llm_output)


system_prompt = """
You are a helpful assistant. Use the `search_wikipedia` tool to find factual information when appropriate.
The user will provide a question string like "Who is Barack Obama?".
1. Extract the item to look up
2. Use the `search_wikipedia` tool to find details
3. Respond clearly to the user, stating the answer found by the tool.
"""

prompt = ChatPrompt(system=system_prompt, user="{question}", agent_class=ADKAgent)

# Optimize it:
optimizer = EvolutionaryOptimizer(
    model="openai/gpt-4o-mini",
    population_size=10,
    num_generations=3,
    enable_moo=False,
    enable_llm_crossover=True,
    infer_output_style=True,
    verbose=1,
    num_threads=1,
)
optimization_result = optimizer.optimize_prompt(
    prompt=prompt,
    dataset=dataset,
    metric=levenshtein_ratio,
    n_samples=10,
)

optimization_result.display()
