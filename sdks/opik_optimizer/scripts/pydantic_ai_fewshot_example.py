from pydantic_ai import Agent

from typing import Any, Dict

from opik.evaluation.metrics.score_result import ScoreResult
from opik.evaluation.metrics import LevenshteinRatio
from opik_optimizer import OptimizableAgent, ChatPrompt, FewShotBayesianOptimizer
from opik_optimizer.datasets import hotpot_300


# Tools:
import dspy


def search_wikipedia(ctx, query: str) -> list[str]:
    """
    This agent is used to search wikipedia. It can retrieve additional details
    about a topic.
    """
    print(ctx)
    results = dspy.ColBERTv2(url="http://20.102.90.50:2017/wiki17_abstracts")(
        query, k=1
    )
    return results[0]["text"]


def levenshtein_ratio(dataset_item: Dict[str, Any], llm_output: str) -> ScoreResult:
    metric = LevenshteinRatio()
    return metric.score(reference=dataset_item["answer"], output=llm_output)


dataset = hotpot_300()

prompt_template = """Use the `search_wikipedia` function to find details
on a topic. Respond with a short, concise answer without
explanation."""


class PydanticAIAgent(OptimizableAgent):
    model = "openai:gpt-4o"
    project_name = "pydantic-ai-agent-wikipedia"
    input_dataset_field = "question"

    def init_agent(self, agent_config):
        self.agent = Agent(
            self.model,
            output_type=str,
            system_prompt=agent_config["chat-prompt"].get_system_prompt(),
        )
        for tool_name in agent_config.get("tools", []):
            tool = agent_config["tools"][tool_name]["function"]
            self.agent.tool(tool)

    def invoke_dataset_item(self, dataset_item, seed=None):
        result = self.agent.run_sync(dataset_item[self.input_dataset_field])
        return result.output


agent_config = {
    "chat-prompt": ChatPrompt(system=prompt_template),
    "tools": {
        "Wikipedia Search": {
            "type": "tool",
            "value": "Search wikipedia for abstracts. Gives a brief paragraph about a topic.",
            "function": search_wikipedia,
        },
    },
}

# Test it:
agent = PydanticAIAgent(agent_config)
result = agent.invoke_dataset_item(
    {"question": "Which is heavier: a newborn elephant, or a motor boat?"}
)
print(result)

# Optimize it:
optimizer = FewShotBayesianOptimizer(
    model="openai/gpt-4o",
    min_examples=3,
    max_examples=8,
    n_threads=16,
    seed=42,
)
result = optimizer.optimize_agent(
    agent_class=PydanticAIAgent,
    agent_config=agent_config,
    dataset=dataset,
    metric=levenshtein_ratio,
    n_trials=10,
    n_samples=50,
)
