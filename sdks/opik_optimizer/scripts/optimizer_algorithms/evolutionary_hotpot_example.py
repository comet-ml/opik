import opik  # noqa: E402
from opik_optimizer import ChatPrompt  # noqa: E402
from opik_optimizer import EvolutionaryOptimizer  # noqa: E402
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

# Initialize the optimizer with custom parameters
optimizer = EvolutionaryOptimizer(
    model="gpt-4o-mini",
    enable_moo=False,
    enable_llm_crossover=True,
    infer_output_style=True,
    verbose=1,
    population_size=10,
    num_generations=3,
)

# Create the optimization configuration

# Optimize the prompt using the optimization config
optimization_result = optimizer.optimize_prompt(
    prompt=prompt,
    dataset=dataset,
    metric=optimization_metric,
    n_samples=10,
    max_trials=5,
)

optimization_result.display()
