from opik_optimizer import ChatPrompt  # noqa: E402
from opik_optimizer import ParameterOptimizer, ParameterSearchSpace  # noqa: E402
from opik_optimizer.datasets import hotpot  # noqa: E402

from utils.metrics import answer_correctness_score


# Load dataset
dataset = hotpot(split="train", count=50)
validation_dataset = hotpot(split="validation", count=25)

# Define initial prompt
system_prompt = (
    "Answer the question with a direct, accurate response."
    + " You have access to a Wikipedia search tool, use it to find relevant information before answering."
    + " Provide concise answers based on the search results."
)


prompt = ChatPrompt(
    system=system_prompt,
    user="{question}",
)

# Define the metric to optimize
optimization_metric = answer_correctness_score

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
    validation_dataset=validation_dataset,
    metric=optimization_metric,
    parameter_space=parameter_space,
    max_trials=2,
    n_samples=20,
)

result.display()
