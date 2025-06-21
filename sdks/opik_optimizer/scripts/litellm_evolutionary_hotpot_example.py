from typing import Dict, Any

from opik_optimizer import EvolutionaryOptimizer

from opik.evaluation.metrics.score_result import ScoreResult
from opik.evaluation.metrics import LevenshteinRatio

from opik_optimizer import OptimizableAgent, ChatPrompt, AgentConfig
from opik_optimizer.datasets import hotpot_300

# For wikipedia tool:
import dspy


def search_wikipedia(query: str) -> list[str]:
    """
    This agent is used to search wikipedia. It can retrieve additional details
    about a topic.
    """
    results = dspy.ColBERTv2(url="http://20.102.90.50:2017/wiki17_abstracts")(
        query, k=3
    )
    return [item["text"] for item in results]


dataset = hotpot_300()


def levenshtein_ratio(dataset_item: Dict[str, Any], llm_output: str) -> ScoreResult:
    metric = LevenshteinRatio()
    return metric.score(reference=dataset_item["answer"], output=llm_output)


class LiteLLMAgent(OptimizableAgent):
    """Agent using LiteLLM for optimization."""

    model = "openai/gpt-4o-mini"
    project_name = "litellm-agent"


system_prompt = """
Answer the question with a direct phrase. Use the tool `search_wikipedia`
if you need it. Make sure you consider the results before answering the
question.
"""

agent_config = AgentConfig(
    chat_prompt=ChatPrompt(system=system_prompt, user="{question}"),
    tools={
        "Search Wikipedia": {
            "function": search_wikipedia,
            "description": "Use this tool to search wikipedia",
            "name": search_wikipedia.__name__,
        }
    },
)

# Test it:
agent = LiteLLMAgent(agent_config)
result = agent.invoke_dataset_item(
    {"question": "Which is heavier: a baby whale or a baby elephant?"}
)
print(result)

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
optimization_result = optimizer.optimize_agent(
    agent_class=LiteLLMAgent,
    agent_config=agent_config,
    dataset=dataset,
    metric=levenshtein_ratio,
    n_samples=10,
)

optimization_result.display()
