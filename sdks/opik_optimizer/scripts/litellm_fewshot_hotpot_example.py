from typing import Dict, Any

from opik.evaluation.metrics.score_result import ScoreResult
from opik.evaluation.metrics import LevenshteinRatio

from opik_optimizer import (
    OptimizableAgent,
    ChatPrompt,
    FewShotBayesianOptimizer,
    AgentConfig,
)
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


def levenshtein_ratio(dataset_item: Dict[str, Any], llm_output: str) -> ScoreResult:
    metric = LevenshteinRatio()
    return metric.score(reference=dataset_item["answer"], output=llm_output)


dataset = hotpot_300()


class LiteLLMAgent(OptimizableAgent):
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

# Optimize it:
optimizer = FewShotBayesianOptimizer(
    model="openai/gpt-4o-mini",
    min_examples=3,
    max_examples=8,
    n_threads=4,
    seed=42,
)
optimization_result = optimizer.optimize_agent(
    agent_class=LiteLLMAgent,
    agent_config=agent_config,
    dataset=dataset,
    metric=levenshtein_ratio,
    n_trials=10,
    n_samples=50,
)
optimization_result.display()
