import opik  # noqa: E402
from opik_optimizer import ChatPrompt  # noqa: E402
from opik_optimizer import ParameterOptimizer, ParameterSearchSpace  # noqa: E402
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
    metric=optimization_metric,
    parameter_space=parameter_space,
    max_trials=2,
    n_samples=5,
)

result.display()
