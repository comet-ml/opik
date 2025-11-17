"""
Example: Use GEPA to optimize a simple system prompt on the tiny_test dataset
using Opik datasets and metrics (LiteLLM models).

Requires:
  pip install gepa
  set OPENAI_API_KEY for LiteLLM-backed models
"""

from typing import Any

from opik.evaluation.metrics import LevenshteinRatio
from opik.evaluation.metrics.score_result import ScoreResult

from opik_optimizer import GepaOptimizer, ChatPrompt, datasets
from opik_optimizer.utils import search_wikipedia


def levenshtein_ratio(dataset_item: dict[str, Any], llm_output: str) -> ScoreResult:
    return LevenshteinRatio().score(reference=dataset_item["label"], output=llm_output)


def main() -> None:
    # Use a small, quick dataset
    dataset = datasets.tiny_test()

    prompt = ChatPrompt(
        name="GEPA_TinyTest",
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
        function_map={
            "search_wikipedia": lambda query: search_wikipedia(query, use_api=True)
        },
    )

    optimizer = GepaOptimizer(
        model="openai/gpt-4o",  # model for GEPA reflection/reasoning
        model_parameters={"temperature": 0.2, "max_tokens": 200},
    )

    result = optimizer.optimize_prompt(
        prompt=prompt,
        dataset=dataset,
        metric=levenshtein_ratio,
        max_trials=12,  # small budget for demo
        reflection_minibatch_size=2,
        n_samples=5,
    )

    result.display()
    # Debug dumps
    details = result.details or {}
    print("\n--- GEPA Debug ---")
    print("gepa_live_metric_used:", details.get("gepa_live_metric_used"))
    print("gepa_live_metric_call_count:", details.get("gepa_live_metric_call_count"))
    print("num_candidates:", details.get("num_candidates"))
    val_scores = details.get("val_scores")
    if isinstance(val_scores, list):
        print("val_aggregate_scores:", [f"{s:.4f}" for s in val_scores])
    print(
        "initial_score:",
        f"{result.initial_score:.4f}" if result.initial_score is not None else None,
    )
    print("final_score:", f"{result.score:.4f}")


if __name__ == "__main__":
    main()
