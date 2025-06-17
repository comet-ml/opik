from typing import Dict, Any, Optional

from pydantic_ai import Agent
from pydantic_ai.tools import RunContext
from pydantic_ai.messages import (
    ModelRequest,
    UserPromptPart,
    SystemPromptPart,
)

from opik.evaluation.metrics.score_result import ScoreResult
from opik.evaluation.metrics import LevenshteinRatio

from opik_optimizer import (
    OptimizableAgent,
    ChatPrompt,
    FewShotBayesianOptimizer,
    AgentConfig,
)
from opik_optimizer.datasets import hotpot_300

# Tools:
import dspy


dataset = hotpot_300()


def search_wikipedia(ctx: RunContext, query: str) -> list[str]:
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


class PydanticAIAgent(OptimizableAgent):
    """Agent using Pydantic AI for optimization."""

    model: str = "openai:gpt-4o"
    project_name: str = "pydantic-ai-agent-wikipedia"

    def init_agent(self, agent_config: AgentConfig) -> None:
        """Initialize the agent with the provided configuration."""
        # Save so that we can get messages:
        self.agent_config = agent_config
        self.agent = Agent(
            self.model,
            output_type=str,
            system_prompt=agent_config.chat_prompt.get_system_prompt(),
        )
        for tool_name in agent_config.tools:
            tool = agent_config.tools[tool_name]["function"]
            self.agent.tool(tool)

    def invoke_dataset_item(
        self, dataset_item: Dict[str, str], seed: Optional[int] = None
    ) -> str:
        """Invoke the agent with a dataset item."""
        # First, get the agent messages, replacing parts with dataset item
        all_messages = self.agent_config.chat_prompt.get_messages(dataset_item)
        messages, user_prompt = all_messages[:-1], all_messages[-1]["content"]

        message_history = []
        for message in messages:
            if message["role"] == "system":
                message_history.append(
                    ModelRequest(parts=[SystemPromptPart(content=message["content"])])
                )
            elif message["role"] == "user":
                message_history.append(
                    ModelRequest(parts=[UserPromptPart(content=message["content"])])
                )
            else:
                raise Exception("Unknown message type: %r" % message)

        result = self.agent.run_sync(user_prompt, message_history=message_history)
        return result.output


tools = {
    "Wikipedia Search": {
        "type": "tool",
        "value": "Search wikipedia for abstracts. Gives a brief paragraph about a topic.",
        "function": search_wikipedia,
    },
}

system_prompt = """Use the `search_wikipedia` function to find details
on a topic. Respond with a short, concise answer without
explanation."""


agent_config = AgentConfig(
    chat_prompt=ChatPrompt(system=system_prompt, prompt="{question}"), tools=tools
)

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
optimization_result = optimizer.optimize_agent(
    agent_class=PydanticAIAgent,
    agent_config=agent_config,
    dataset=dataset,
    metric=levenshtein_ratio,
    n_trials=10,
    n_samples=50,
)
optimization_result.display()
