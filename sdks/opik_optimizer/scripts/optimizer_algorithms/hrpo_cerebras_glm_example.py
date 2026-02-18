"""
HRPO (Hierarchical Reflective Prompt Optimizer) example using Cerebras GLM.

This script demonstrates:
- Using a Cerebras model (zai-glm-4.7) for task responses
- Using a fast GPT-5.2 model for HRPO analysis/improvement
- Optimizing prompts with tool calling support

Required env vars:
- CEREBRAS_API_KEY (for the task model)
- OPENAI_API_KEY (for HRPO + judge models)
"""

import opik  # noqa: E402
from opik_optimizer import ChatPrompt, HRPO  # noqa: E402
from opik_optimizer.datasets import hotpot  # noqa: E402
from opik_optimizer.utils.tools.wikipedia import search_wikipedia  # noqa: E402

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
    tools=[
        {
            "type": "function",
            "function": {
                "name": "search_wikipedia",
                "description": "Search Wikipedia for information about a topic. Returns relevant article abstracts.",
                "parameters": {
                    "type": "object",
                    "properties": {
                        "query": {
                            "type": "string",
                            "description": "The search query - a topic, person, place, or concept to look up.",
                        },
                    },
                    "required": ["query"],
                },
            },
        },
    ],
    function_map={
        "search_wikipedia": opik.track(type="tool")(
            lambda query: search_wikipedia(query, search_type="api")
        )
    },
    model="cerebras/zai-glm-4.7",
    model_parameters={"temperature": 0.2, "max_tokens": 10000},
)

# Define the metric to optimize
optimization_metric = answer_correctness_score

# Initialize HRPO (Hierarchical Reflective Prompt Optimizer)
optimizer = HRPO(
    model="openai/gpt-5.2",  # Fast analysis model for improvement steps
    n_threads=4,
    max_parallel_batches=3,
    model_parameters={"temperature": 0.7, "max_tokens": 4096},
    seed=42,
    verbose=1,
)

# Run optimization
optimization_result = optimizer.optimize_prompt(
    prompt=prompt,
    dataset=dataset,
    validation_dataset=validation_dataset,
    metric=optimization_metric,
    max_trials=5,
    n_samples=10,
    max_retries=0,
)

optimization_result.display()
