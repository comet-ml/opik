"""
GEPA example on a Hotpot-style dataset using LiteLLM models.

Notes:
- Ensures local `src/` is imported so the new GEPA optimizer is used.
- Makes `dspy` optional; falls back to a no-op search tool if not installed.
"""

from typing import Any
import os
import sys

# Prefer local src/ over installed package during development
_HERE = os.path.abspath(os.path.dirname(__file__))
_SRC = os.path.abspath(os.path.join(_HERE, "..", "src"))
if os.path.isdir(_SRC) and _SRC not in sys.path:
    sys.path.insert(0, _SRC)

import opik  # noqa: E402
from opik_optimizer import ChatPrompt  # noqa: E402
from opik_optimizer.gepa_optimizer import GepaOptimizer  # noqa: E402
from opik_optimizer.datasets import hotpot_300  # noqa: E402
from opik_optimizer.utils import search_wikipedia  # noqa: E402

from opik.evaluation.metrics import LevenshteinRatio  # noqa: E402
from opik.evaluation.metrics.score_result import ScoreResult  # noqa: E402


dataset = hotpot_300()


def levenshtein_ratio(dataset_item: dict[str, Any], llm_output: str) -> ScoreResult:
    metric = LevenshteinRatio()
    return metric.score(reference=dataset_item["answer"], output=llm_output)


system_prompt = "Answer the question"

prompt = ChatPrompt(
    project_name="GEPA-Hotpot",
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
    function_map={
        "search_wikipedia": opik.track(type="tool")(
            lambda query: search_wikipedia(query, use_api=True)
        )
    },
)


# Optimize it with GEPA
optimizer = GepaOptimizer(
    model="openai/gpt-4o-mini",  # smaller task model (valid LiteLLM)
    reflection_model="openai/gpt-4o",  # larger reflection model (valid LiteLLM)
    temperature=0.0,  # deterministic completions during optimization
    max_tokens=400,
)

result = optimizer.optimize_prompt(
    prompt=prompt,
    dataset=dataset,
    metric=levenshtein_ratio,
    n_samples=12,
    max_metric_calls=60,
    reflection_minibatch_size=5,
    candidate_selection_strategy="best",
    display_progress_bar=True,
)

details = result.details or {}
summary = details.get("candidate_summary", [])

print("\n=== GEPA Candidate Scores ===")
for idx, row in enumerate(summary):
    print(
        f"#{idx:02d} source={row.get('source')} GEPA={row.get('gepa_score')} "
        f"Opik={row.get('opik_score')}"
    )

print("\nSelected candidate:")
print("  index:", details.get("selected_candidate_index"))
print("  GEPA score:", details.get("selected_candidate_gepa_score"))
print("  Opik score:", details.get("selected_candidate_opik_score"))

print("\nPer-item scores for selected prompt:")
for record in details.get("selected_candidate_item_scores", []):
    print(
        f"  id={record.get('dataset_item_id')} score={record.get('score'):.4f} "
        f"answer={record.get('answer')} output={record.get('output', '')[:60]}"
    )
