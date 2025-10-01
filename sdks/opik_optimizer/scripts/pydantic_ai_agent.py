from opik_optimizer import (
    OptimizableAgent,
    ChatPrompt,
)
from opik_optimizer.utils import search_wikipedia
from opik import track

# Requires pydantic_ai version 0.2.15 or greater:
from pydantic_ai import Agent
from pydantic_ai.tools import RunContext
from pydantic_ai.messages import (
    ModelRequest,
    UserPromptPart,
    SystemPromptPart,
)


def search_wikipedia_tool(ctx: RunContext, query: str) -> list[str]:
    """
    This agent is used to search wikipedia. It can retrieve additional details
    about a topic.
    """
    return search_wikipedia(query)


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
        self.agent.tool(track(type="tool")(search_wikipedia_tool))

    def invoke(self, messages: list[dict[str, str]], seed: int | None = None) -> str:
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
