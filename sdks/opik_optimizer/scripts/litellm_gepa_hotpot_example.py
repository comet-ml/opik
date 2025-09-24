"""
GEPA example on a Hotpot-style dataset using LiteLLM models.

Notes:
- Ensures local `src/` is imported so the new GEPA optimizer is used.
- Makes `dspy` optional; falls back to a no-op search tool if not installed.
"""

from typing import Any, Dict
import os
import sys

# Prefer local src/ over installed package during development
_HERE = os.path.abspath(os.path.dirname(__file__))
_SRC = os.path.abspath(os.path.join(_HERE, "..", "src"))
if os.path.isdir(_SRC) and _SRC not in sys.path:
    sys.path.insert(0, _SRC)

import opik
from opik_optimizer import ChatPrompt
from opik_optimizer.gepa_optimizer import GepaOptimizer
from opik_optimizer.datasets import hotpot_300
from opik_optimizer.utils import search_wikipedia

from opik.evaluation.metrics import LevenshteinRatio
from opik.evaluation.metrics.score_result import ScoreResult


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
    function_map={"search_wikipedia": opik.track(type="tool")(search_wikipedia)},
)


# Optimize it with GEPA
optimizer = GepaOptimizer(
    model="openai/gpt-4o-mini",  # smaller task model (valid LiteLLM)
    reflection_model="openai/gpt-4o",  # larger reflection model (valid LiteLLM)
    project_name="GEPA-Hotpot",
    temperature=0.0,  # deterministic completions during optimization
    max_tokens=400,
)

result = optimizer.optimize_prompt(
    prompt=prompt,
    dataset=dataset,
    metric=levenshtein_ratio,
    max_metric_calls=60,  # slight budget increase
    reflection_minibatch_size=5,  # small bump
    candidate_selection_strategy="best",
    n_samples=12,  # test on a few more items
)

result.display()

# Debug dumps (GEPA internal + Opik)
details = result.details or {}
print("\n--- GEPA Debug ---")
print("gepa_live_metric_used:", details.get("gepa_live_metric_used"))
print("gepa_live_metric_call_count:", details.get("gepa_live_metric_call_count"))
print("num_candidates:", details.get("num_candidates"))
val_scores = details.get("val_scores")
if isinstance(val_scores, list):
    print("val_aggregate_scores:", [f"{s:.4f}" for s in val_scores])
