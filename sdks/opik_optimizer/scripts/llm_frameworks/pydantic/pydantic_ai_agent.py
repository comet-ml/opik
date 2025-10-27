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
            result_type=str,
            system_prompt="",  # We'll use the chat-prompt
        )
        self.agent.tool(track(type="tool")(search_wikipedia_tool))

    def invoke(self, messages: list[dict[str, str]], seed: int | None = None) -> str:
        # Extract user prompt and system messages
        user_prompt = None
        message_history = []

        for message in messages:
            if message["role"] == "system":
                message_history.append(
                    ModelRequest(parts=[SystemPromptPart(content=message["content"])])
                )
            elif message["role"] == "user":
                # The last user message becomes the user_prompt
                user_prompt = message["content"]
                # Earlier user messages go in history
                if user_prompt != message["content"]:
                    message_history.append(
                        ModelRequest(parts=[UserPromptPart(content=message["content"])])
                    )
            else:
                raise Exception("Unknown message type: %r" % message)

        # If no user prompt found, use empty string
        if user_prompt is None:
            user_prompt = ""

        result = self.agent.run_sync(
            user_prompt=user_prompt,
            message_history=message_history if message_history else None,
        )
        return result.data
