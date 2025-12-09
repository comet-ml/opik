from __future__ import annotations

from typing import Any, TYPE_CHECKING

from opik_optimizer import OptimizableAgent
from opik_optimizer.utils.tools.wikipedia import search_wikipedia
from opik import track
from pydantic_ai import Agent
from pydantic_ai.tools import RunContext
from pydantic_ai.messages import ModelRequest, UserPromptPart, SystemPromptPart

if TYPE_CHECKING:
    from opik_optimizer.api_objects import chat_prompt


@track(type="tool")
def search_wikipedia_tool(_ctx: RunContext, query: str) -> list[str]:
    """Search Wikipedia for information about a topic."""
    return search_wikipedia(query, search_type="api")


class PydanticAIAgent(OptimizableAgent):
    project_name = "pydantic-ai-agent"

    def invoke_agent(
        self,
        prompts: dict[str, chat_prompt.ChatPrompt],
        dataset_item: dict[str, Any],
        allow_tool_use: bool = False,
        seed: int | None = None,
    ) -> str:
        if len(prompts) > 1:
            raise ValueError(
                "PydanticAIAgent only supports single-prompt optimization."
            )

        prompt = list(prompts.values())[0]
        messages = prompt.get_messages(dataset_item)
        if not messages:
            return "No messages generated from prompt."

        # Extract system prompt and user question
        system_text = next(
            (m["content"] for m in messages if m["role"] == "system"), ""
        )
        user_prompt = next(
            (m["content"] for m in reversed(messages) if m["role"] == "user"), ""
        )

        # Create Pydantic AI agent
        model_name = prompt.model or "openai:gpt-4o-mini"
        agent = Agent(model_name, output_type=str, system_prompt=system_text)
        agent.tool(search_wikipedia_tool)

        # Build message history (excluding the last user message)
        message_history = []
        for message in messages[:-1]:
            if message["role"] == "system":
                message_history.append(
                    ModelRequest(parts=[SystemPromptPart(content=message["content"])])
                )
            elif message["role"] == "user":
                message_history.append(
                    ModelRequest(parts=[UserPromptPart(content=message["content"])])
                )

        result = agent.run_sync(
            user_prompt=user_prompt,
            message_history=message_history if message_history else None,
        )
        return result.output
