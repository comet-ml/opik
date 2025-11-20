from typing import Any

import opik
import opik_optimizer
from opik_optimizer import ChatPrompt
from opik_optimizer import GepaOptimizer
from opik_optimizer.datasets import hotpot
from opik_optimizer.utils import search_wikipedia

from opik.evaluation.metrics import LevenshteinRatio, Equals
from opik.evaluation.metrics.score_result import ScoreResult


# Use test_mode to avoid heavy downloads when running the example locally.
dataset = hotpot(count=300, test_mode=True)


def levenshtein_ratio(dataset_item: dict[str, Any], llm_output: str) -> ScoreResult:
    metric = LevenshteinRatio()
    return metric.score(reference=dataset_item["answer"], output=llm_output)


def equals(dataset_item: dict[str, Any], llm_output: str) -> ScoreResult:
    metric = Equals()
    return metric.score(reference=dataset_item["answer"], output=llm_output)


prompt = ChatPrompt(
    system="Answer the question",
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
    function_map={"search_wikipedia": opik.track(type="tool")(search_wikipedia)},
)

optimizer = GepaOptimizer(
    model="openai/gpt-4o",  # model for GEPA reflection/reasoning
    model_parameters={"temperature": 0.7, "max_tokens": 400},
)

multi_metric_objective = opik_optimizer.MultiMetricObjective(
    weights=[0.6, 0.4],
    metrics=[levenshtein_ratio, equals],
    name="my_composite_metric",
)

result = optimizer.optimize_prompt(
    prompt=prompt,
    dataset=dataset,
    metric=multi_metric_objective,
    max_trials=5,
    n_samples=12,
    reflection_minibatch_size=5,
    candidate_selection_strategy="pareto",
)

result.display()
