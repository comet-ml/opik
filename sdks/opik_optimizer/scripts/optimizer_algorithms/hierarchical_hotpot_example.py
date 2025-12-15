"""
HRPO (Hierarchical Reflective Prompt Optimizer) example on Hotpot dataset using LiteLLM.

This script demonstrates:
- Using HRPO to systematically improve prompts
- Creating a custom LLM-as-judge metric for semantic similarity
- The importance of metrics that provide reasoning for root cause analysis
- Optimizing prompts with tool calling support

Note: HRPO requires metrics that return ScoreResult with detailed 'reason'
fields for effective root cause analysis.
"""

import opik  # noqa: E402
from opik_optimizer import ChatPrompt, HRPO  # noqa: E402
from opik_optimizer.datasets import hotpot  # noqa: E402
from opik_optimizer.utils import search_wikipedia  # noqa: E402

from utils.metrics import answer_correctness_score


# Load dataset
dataset = hotpot(count=300)

# Define initial prompt
system_prompt = """Answer the question with a direct, accurate response.
You have access to a Wikipedia search tool - use it to find relevant information before answering.
Provide concise answers based on the search results."""


@opik.track(type="tool")
def search_wikipedia_tool(query: str) -> list[str]:
    return search_wikipedia(query, use_api=True)


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
    function_map={"search_wikipedia": search_wikipedia_tool},
)

# Define the metric to optimize
optimization_metric = answer_correctness_score

# Initialize HRPO (Hierarchical Reflective Prompt Optimizer)
optimizer = HRPO(
    model="openai/gpt-4o",  # Model for analysis and improvement
    n_threads=4,  # Parallel evaluation threads
    max_parallel_batches=3,  # Batches analyzed concurrently
    model_parameters={"temperature": 0.7, "max_tokens": 4096},
    seed=42,
    verbose=1,  # Show progress
)

# Run optimization
optimization_result = optimizer.optimize_prompt(
    prompt=prompt,
    dataset=dataset,
    metric=answer_correctness_score,
    max_trials=5,
    n_samples=10,
    max_retries=0,
)

optimization_result.display()
