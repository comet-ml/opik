from typing import Any

from opik_optimizer.datasets import driving_hazard
from opik_optimizer import ChatPrompt, HRPO
from opik.evaluation.metrics import LevenshteinRatio
from opik.evaluation.metrics.score_result import ScoreResult

# Import the dataset
dataset = driving_hazard(count=20)
validation_dataset = driving_hazard(split="test", count=5)


# Define the metric to optimize on
def levenshtein_ratio(dataset_item: dict[str, Any], llm_output: str) -> ScoreResult:
    metric = LevenshteinRatio()
    metric_score = metric.score(reference=dataset_item["hazard"], output=llm_output)
    return ScoreResult(
        value=metric_score.value,
        name=metric_score.name,
        reason=f"Levenshtein ratio between `{dataset_item['hazard']}` and `{llm_output}` is `{metric_score.value}`.",
    )


# Define the prompt to optimize
system_prompt = """You are an expert driving safety assistant specialized in hazard detection.

Your task is to analyze dashcam images and identify potential hazards that a driver should be aware of.

For each image:
1. Carefully examine the visual scene
2. Identify any potential hazards (pedestrians, vehicles, road conditions, obstacles, etc.)
3. Assess the urgency and severity of each hazard
4. Provide a clear, specific description of the hazard

Be precise and actionable in your hazard descriptions. Focus on safety-critical information."""

prompt = ChatPrompt(
    messages=[
        {"role": "system", "content": system_prompt},
        {
            "role": "user",
            "content": [
                {"type": "text", "text": "{question}"},
                {
                    "type": "image_url",
                    "image_url": {
                        "url": "{image}",
                    },
                },
            ],
        },
    ],
)

# Initialize HRPO (Hierarchical Reflective Prompt Optimizer)
optimizer = HRPO(model="openai/gpt-5.2", model_parameters={"temperature": 1})

# Run optimization
optimization_result = optimizer.optimize_prompt(
    prompt=prompt,
    dataset=dataset,
    validation_dataset=validation_dataset,
    metric=levenshtein_ratio,
    max_trials=10,
)

# Show results
optimization_result.display()
