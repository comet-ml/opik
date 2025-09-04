"""
GEPA example on a Hotpot-style dataset using LiteLLM models.

Notes:
- Ensures local `src/` is imported so the new GEPA optimizer is used.
- Makes `dspy` optional; falls back to a no-op search tool if not installed.
"""

from typing import Any, Dict

from opik_optimizer import ChatPrompt
from opik_optimizer.gepa_optimizer import GepaOptimizer
from opik_optimizer.datasets import hotpot_300

from opik.evaluation.metrics import LevenshteinRatio
from opik.evaluation.metrics.score_result import ScoreResult

# Optional tool used by the prompt (same style as other examples)
import dspy


def search_wikipedia(query: str) -> list[str]:
    results = dspy.ColBERTv2(url="http://20.102.90.50:2017/wiki17_abstracts")(query, k=3)
    return [item["text"] for item in results]


dataset = hotpot_300()


def levenshtein_ratio(dataset_item: Dict[str, Any], llm_output: str) -> ScoreResult:
    metric = LevenshteinRatio()
    return metric.score(reference=dataset_item["answer"], output=llm_output)


system_prompt = "Answer the question"

prompt = ChatPrompt(
    system=system_prompt,
    user="{question}",
    tools=[
        {
            "type": "function",
            "function": {
                "name": "search_wikipedia",
                "description": "This function is used to search wikipedia abstracts.",
                "parameters": {
                    "type": "object",
                    "properties": {
                        "query": {
                            "type": "string",
                            "description": "The query parameter is the term or phrase to search for.",
                        },
                    },
                    "required": ["query"],
                },
            },
        },
    ],
    function_map={"search_wikipedia": search_wikipedia},
)


# Optimize it with GEPA
optimizer = GepaOptimizer(
    model="openai/gpt-4o-mini",
    reflection_model="openai/gpt-4o-mini",  # consider stronger reflector if available
    project_name="GEPA-Hotpot",
    temperature=0.7,  # slight increase for more exploration
    max_tokens=400,
)

result = optimizer.optimize_prompt(
    prompt=prompt,
    dataset=dataset,
    metric=levenshtein_ratio,
    max_metric_calls=60,              # slight budget increase
    reflection_minibatch_size=5,      # small bump
    candidate_selection_strategy="best",
    n_samples=12,                     # test on a few more items
)

result.display()
