"""Example: optimize LiteLLM parameters on the HotpotQA dataset."""

from __future__ import annotations

import sys
from typing import Any

from opik.evaluation.metrics import LevenshteinRatio
from opik.evaluation.metrics.score_result import ScoreResult
from opik.rest_api.core import ApiError

from opik_optimizer import ChatPrompt, ParameterOptimizer, ParameterSearchSpace
from opik_optimizer.datasets import hotpot_300


def levenshtein_ratio(dataset_item: dict[str, Any], llm_output: str) -> ScoreResult:
    metric = LevenshteinRatio()
    return metric.score(reference=dataset_item["answer"], output=llm_output)


def load_dataset() -> "opik.Dataset":  # type: ignore[name-defined]
    try:
        return hotpot_300(test_mode=True)
    except ApiError as exc:
        print(
            "Failed to load HotpotQA dataset from Opik.\n"
            "Ensure an Opik server is running and OPIK_API_KEY is set, then try again.",
            file=sys.stderr,
        )
        raise


def main() -> None:
    dataset = load_dataset()

    prompt = ChatPrompt(
        system=(
            "You are a helpful assistant that answers questions with concise, factual"
            " statements."
        ),
        user="Question: {question}\nAnswer:",
        model="openai/gpt-4o-mini",
        temperature=0.7,
        top_p=0.95,
    )

    optimizer = ParameterOptimizer(
        model="openai/gpt-4o-mini",
        default_n_trials=20,
        n_threads=4,
        seed=42,
    )

    parameter_space = ParameterSearchSpace.model_validate(
        {
            "temperature": {"type": "float", "min": 0.0, "max": 1.0},
            "top_p": {"type": "float", "min": 0.3, "max": 1.0},
            "frequency_penalty": {
                "type": "float",
                "min": -1.0,
                "max": 1.0,
            },
        }
    )

    result = optimizer.optimize_parameter(
        prompt=prompt,
        dataset=dataset,
        metric=levenshtein_ratio,
        parameter_space=parameter_space,
        n_trials=20,
        n_samples=5,
    )

    result.display()


if __name__ == "__main__":
    main()
