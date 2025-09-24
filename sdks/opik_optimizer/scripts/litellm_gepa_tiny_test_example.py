"""
Example: Use GEPA to optimize a simple system prompt on the tiny_test dataset
using Opik datasets and metrics (LiteLLM models).

Requires:
  pip install gepa
  set OPENAI_API_KEY for LiteLLM-backed models
"""

from typing import Any, Dict

from opik.evaluation.metrics import LevenshteinRatio
from opik.evaluation.metrics.score_result import ScoreResult

from opik_optimizer import ChatPrompt, datasets
from opik_optimizer.gepa_optimizer import GepaOptimizer
from opik_optimizer.utils import search_wikipedia


def levenshtein_ratio(dataset_item: Dict[str, Any], llm_output: str) -> ScoreResult:
    return LevenshteinRatio().score(reference=dataset_item["label"], output=llm_output)


def main() -> None:
    # Use a small, quick dataset
    dataset = datasets.tiny_test()

    prompt = ChatPrompt(
        system=(
            "You are a helpful assistant. Use the `search_wikipedia` tool when needed and "
            "answer concisely with the exact answer string."
        ),
        user="{text}",
        tools=[
            {
                "type": "function",
                "function": {
                    "name": "search_wikipedia",
                    "description": "This function searches Wikipedia abstracts.",
                    "parameters": {
                        "type": "object",
                        "properties": {
                            "query": {
                                "type": "string",
                                "description": "The term or phrase to search for.",
                            }
                        },
                        "required": ["query"],
                    },
                },
            }
        ],
        function_map={"search_wikipedia": search_wikipedia},
    )

    optimizer = GepaOptimizer(
        model="openai/gpt-4o-mini",
        reflection_model="openai/gpt-4o",  # stronger reflector, optional
        project_name="GEPA_TinyTest",
        temperature=0.2,
        max_tokens=200,
    )

    result = optimizer.optimize_prompt(
        prompt=prompt,
        dataset=dataset,
        metric=levenshtein_ratio,
        max_metric_calls=12,  # small budget for demo
        reflection_minibatch_size=2,
        n_samples=5,
    )

    result.display()


if __name__ == "__main__":
    main()
