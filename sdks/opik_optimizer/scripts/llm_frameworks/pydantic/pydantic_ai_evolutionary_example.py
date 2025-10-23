from typing import Any


from opik.evaluation.metrics.score_result import ScoreResult
from opik.evaluation.metrics import LevenshteinRatio

from opik_optimizer import (
    ChatPrompt,
    EvolutionaryOptimizer,
)
from opik_optimizer.datasets import hotpot_300
from pydantic_ai_agent import PydanticAIAgent


def levenshtein_ratio(dataset_item: dict[str, Any], llm_output: str) -> ScoreResult:
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
optimizer = EvolutionaryOptimizer(
    model="openai/gpt-4o-mini",  # Using gpt-4o-mini for evaluation for speed
    enable_moo=False,
    enable_llm_crossover=True,
    infer_output_style=True,
    verbose=1,
    n_threads=4,
    population_size=10,
    num_generations=3,
)

optimization_result = optimizer.optimize_prompt(
    prompt=prompt,
    agent_class=PydanticAIAgent,
    dataset=dataset,
    metric=levenshtein_ratio,
    n_samples=10,
)
optimization_result.display()
