from typing import Dict, Any

from opik.evaluation.metrics.score_result import ScoreResult
from opik.evaluation.metrics import LevenshteinRatio

from opik_optimizer import (
    ChatPrompt,
    FewShotBayesianOptimizer,
)
from opik_optimizer.datasets import hotpot_300
from opik_optimizer.utils import search_wikipedia

# NOTE: functions are automatically tracked in the ChatPrompt


def levenshtein_ratio(dataset_item: Dict[str, Any], llm_output: str) -> ScoreResult:
    metric = LevenshteinRatio()
    return metric.score(reference=dataset_item["answer"], output=llm_output)


dataset = hotpot_300()

optimizer = FewShotBayesianOptimizer(
    model="openai/gpt-4o-mini",
    min_examples=3,
    max_examples=8,
    n_threads=4,
    seed=42,
)

system_prompt = """
Answer the question with a direct phrase. Use the tool `search_wikipedia`
if you need it. Make sure you consider the results before answering the
question.
"""

prompt = ChatPrompt(
    system=system_prompt,
    user="{question}",
    # Values for the ChatPrompt LLM
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

optimization_result = optimizer.optimize_prompt(
    prompt=prompt,
    dataset=dataset,
    metric=levenshtein_ratio,
    n_trials=10,
    n_samples=50,
)
optimization_result.display()
