#!/usr/bin/env python3
"""
CrewAI + Opik Evolutionary Optimizer example.

Prerequisites:
    pip install crewai langchain-openai

Environment variables:
    export OPENAI_API_KEY=<your-api-key>
"""

from typing import Any

from opik.evaluation.metrics import LevenshteinRatio
from opik.evaluation.metrics.score_result import ScoreResult
from opik_optimizer import ChatPrompt, EvolutionaryOptimizer
from opik_optimizer.datasets import hotpot
from crewai_agent import CrewAIAgent


def levenshtein_ratio(dataset_item: dict[str, Any], llm_output: str) -> ScoreResult:
    metric = LevenshteinRatio()
    return metric.score(reference=dataset_item["answer"], output=llm_output)


dataset = hotpot(count=25)

prompt = ChatPrompt(
    system=(
        "You are a research assistant with access to Wikipedia. "
        "Always use the search_wikipedia tool to find accurate information. "
        "Provide concise, factual answers based on the information found."
    ),
    user="{question}",
)

optimizer = EvolutionaryOptimizer(
    model="openai/gpt-4o-mini",
    enable_moo=False,
    enable_llm_crossover=True,
    infer_output_style=True,
    verbose=1,
    n_threads=2,
    population_size=5,
    num_generations=2,
)

optimization_result = optimizer.optimize_prompt(
    prompt=prompt,
    agent_class=CrewAIAgent,
    dataset=dataset,
    metric=levenshtein_ratio,
    n_samples=5,
)

optimization_result.display()
