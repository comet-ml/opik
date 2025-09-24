"""
Example: Use GEPA to optimize a simple system prompt on the tiny_test dataset
using Opik datasets and metrics (LiteLLM models).

Requires:
  pip install gepa
  set OPENAI_API_KEY for LiteLLM-backed models
"""

from typing import Any, Dict
import os
import sys

# Prefer local src/ over installed package during development
_HERE = os.path.abspath(os.path.dirname(__file__))
_SRC = os.path.abspath(os.path.join(_HERE, "..", "src"))
if os.path.isdir(_SRC) and _SRC not in sys.path:
    sys.path.insert(0, _SRC)

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
