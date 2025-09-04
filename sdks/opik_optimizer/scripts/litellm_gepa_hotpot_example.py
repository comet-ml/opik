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


system_prompt = """
Answer the question with a direct phrase. Use the tool `search_wikipedia`
if you need it. Make sure you consider the results before answering the
question.
"""

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
    reflection_model="openai/gpt-4o-mini",  # can be stronger if desired
    project_name="GEPA-Hotpot",
    temperature=0.2,
    max_tokens=400,
)

result = optimizer.optimize_prompt(
    prompt=prompt,
    dataset=dataset,
    metric=levenshtein_ratio,
    max_metric_calls=20,
    reflection_minibatch_size=3,
    n_samples=10,
)

result.display()

