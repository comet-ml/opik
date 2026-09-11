from __future__ import annotations

import asyncio
from typing import Any, TYPE_CHECKING

from opik import track
from opik_optimizer import OptimizableAgent
from opik_optimizer.utils.tools.wikipedia import search_wikipedia
from agent_framework.openai import OpenAIChatClient

if TYPE_CHECKING:
    from opik_optimizer.api_objects import chat_prompt


@track(type="tool")
def search_wikipedia_tracked(query: str) -> list[str]:
    """Search Wikipedia for information about a topic."""
    return search_wikipedia(query, search_type="api")


class MicrosoftAgentFrameworkAgent(OptimizableAgent):
    project_name = "microsoft-agent-framework"

    def invoke_agent(
        self,
        prompts: dict[str, chat_prompt.ChatPrompt],
        dataset_item: dict[str, Any],
        allow_tool_use: bool = False,
        seed: int | None = None,
    ) -> str:
        if len(prompts) > 1:
            raise ValueError(
                "MicrosoftAgentFrameworkAgent only supports single-prompt optimization."
            )

        prompt = list(prompts.values())[0]
        messages = prompt.get_messages(dataset_item)
        if not messages:
            return "No messages generated from prompt."

        # Extract system prompt and user question
        system_text = next(
            (m["content"] for m in messages if m["role"] == "system"), ""
        )
        question = next(
            (m["content"] for m in reversed(messages) if m["role"] == "user"), ""
        )

        # Create agent
        model_id = prompt.model or "gpt-4o-mini"
        client = OpenAIChatClient(model_id=model_id)
        agent = client.create_agent(
            name=self.project_name,
            instructions=system_text,
            tools=[search_wikipedia_tracked],
        )

        async def run_agent() -> Any:
            return await agent.run(question)

        result = asyncio.run(run_agent())
        return str(result)
