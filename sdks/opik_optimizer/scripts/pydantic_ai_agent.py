from typing import Optional, List, Dict

from opik_optimizer import (
    OptimizableAgent,
    ChatPrompt,
)

from pydantic_ai import Agent
from pydantic_ai.tools import RunContext
from pydantic_ai.messages import (
    ModelRequest,
    UserPromptPart,
    SystemPromptPart,
)

# Tools:
import dspy


def search_wikipedia(ctx: RunContext, query: str) -> list[str]:
    """
    This agent is used to search wikipedia. It can retrieve additional details
    about a topic.
    """
    results = dspy.ColBERTv2(url="http://20.102.90.50:2017/wiki17_abstracts")(
        query, k=3
    )
    return [item["text"] for item in results]


class PydanticAIAgent(OptimizableAgent):
    """Agent using Pydantic AI for optimization."""

    project_name: str = "pydantic-ai-agent"

    def init_agent(self, prompt: ChatPrompt) -> None:
        """Initialize the agent with the provided configuration."""
        # This agent doesn't actually change the agent, so we just initialize it:
        self.agent = Agent(
            "openai:gpt-4o",
            output_type=str,
            system_prompt="",  # We'll use the chat-prompt
        )
        self.agent.tool(search_wikipedia)

    def invoke(self, messages: List[Dict[str, str]], seed: Optional[int] = None) -> str:
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

        result = self.agent.run_sync(message_history=message_history)
        return result.output
