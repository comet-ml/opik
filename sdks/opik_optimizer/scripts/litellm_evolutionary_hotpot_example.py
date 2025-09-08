from typing import Dict, Any

from opik_optimizer import EvolutionaryOptimizer

from opik.evaluation.metrics.score_result import ScoreResult
from opik.evaluation.metrics import LevenshteinRatio

from opik_optimizer import ChatPrompt
from opik_optimizer.datasets import hotpot_300
from opik_optimizer.utils import search_wikipedia

# NOTE: functions are automatically tracked in the ChatPrompt

dataset = hotpot_300()


def levenshtein_ratio(dataset_item: Dict[str, Any], llm_output: str) -> ScoreResult:
    metric = LevenshteinRatio()
    return metric.score(reference=dataset_item["answer"], output=llm_output)


system_prompt = """
Answer the question with a direct phrase. Use the tool `search_wikipedia`
if you need it. Make sure you consider the results before answering the
question.
"""

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
    function_map={"search_wikipedia": search_wikipedia},
)

# Initialize the optimizer with custom parameters
optimizer = EvolutionaryOptimizer(
    model="gpt-4o-mini",
    population_size=10,
    num_generations=3,
    enable_moo=False,
    enable_llm_crossover=True,
    infer_output_style=True,
    verbose=1,
)

# Create the optimization configuration

# Optimize the prompt using the optimization config
optimization_result = optimizer.optimize_prompt(
    prompt=prompt,
    dataset=dataset,
    metric=levenshtein_ratio,
    n_samples=10,
)

optimization_result.display()
