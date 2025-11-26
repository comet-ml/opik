from typing import Any


from opik.evaluation.metrics.score_result import ScoreResult
from opik.evaluation.metrics import LevenshteinRatio

from opik_optimizer import (
    ChatPrompt,
    EvolutionaryOptimizer,
)
from opik_optimizer.datasets import hotpot
from opik_optimizer.utils import search_wikipedia
from pydantic_ai_agent import PydanticAIAgent


def levenshtein_ratio(dataset_item: dict[str, Any], llm_output: str) -> ScoreResult:
    metric = LevenshteinRatio()
    return metric.score(reference=dataset_item["answer"], output=llm_output)


dataset = hotpot(count=25)

system_prompt = """You are a helpful assistant with access to a search_wikipedia tool.
When answering questions, use the search_wikipedia tool to find accurate information.
Always call the tool before providing your answer. Respond with a short, concise answer."""


prompt = ChatPrompt(
    system=system_prompt,
    user="{question}",
    tools=[
        {
            "type": "function",
            "function": {
                "name": "search_wikipedia",
                "description": "Search Wikipedia for information about a topic. Returns relevant article abstracts.",
                "parameters": {
                    "type": "object",
                    "properties": {
                        "query": {
                            "type": "string",
                            "description": "The search query - a topic, person, place, or concept to look up.",
                        },
                    },
                    "required": ["query"],
                },
            },
        },
    ],
    function_map={"search_wikipedia": search_wikipedia},
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
    agent_class=PydanticAIAgent,
    dataset=dataset,
    metric=levenshtein_ratio,
    n_samples=5,
)
optimization_result.display()
