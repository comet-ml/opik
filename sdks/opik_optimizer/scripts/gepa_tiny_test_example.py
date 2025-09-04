"""
Example: Use GEPA to optimize a simple system prompt on the tiny_test dataset
using Opik datasets and metrics.

Requires:
  pip install gepa
  set OPENAI_API_KEY for LiteLLM-backed models
"""

from typing import Any, Dict

from opik.evaluation.metrics import LevenshteinRatio
from opik.evaluation.metrics.score_result import ScoreResult

from opik_optimizer import TaskConfig, datasets
from opik_optimizer.gepa_optimizer import GepaOptimizer


def levenshtein_ratio(dataset_item: Dict[str, Any], llm_output: str) -> ScoreResult:
    return LevenshteinRatio().score(
        reference=dataset_item["label"], output=llm_output
    )


def main() -> None:
    # Use a small, quick dataset
    dataset = datasets.tiny_test()

    task_config = TaskConfig(
        instruction_prompt=(
            "You are a helpful assistant. Answer concisely with the exact answer string."
        ),
        input_dataset_fields=["text"],
        output_dataset_field="label",
        use_chat_prompt=True,
        tools=[],
    )

    optimizer = GepaOptimizer(
        model="openai/gpt-4o-mini",
        reflection_model="openai/gpt-5",  # stronger reflector, optional
        project_name="GEPA_TinyTest",
        temperature=0.2,
        max_tokens=200,
    )

    result = optimizer.optimize_prompt(
        dataset=dataset,
        metric=levenshtein_ratio,
        task_config=task_config,
        max_metric_calls=12,  # small budget for demo
        reflection_minibatch_size=2,
        n_samples=5,
    )

    print(result)


if __name__ == "__main__":
    main()
