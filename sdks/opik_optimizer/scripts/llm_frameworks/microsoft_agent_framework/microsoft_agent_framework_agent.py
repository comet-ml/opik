from __future__ import annotations

import asyncio
from typing import Any

from opik import track
from opik_optimizer import ChatPrompt, OptimizableAgent
from opik_optimizer.utils import search_wikipedia
from opik_optimizer.utils.llm_logger import LLMLogger
from agent_framework.openai import OpenAIChatClient

# Setup logger with suppressed framework logs
logger = LLMLogger("microsoft_agent_framework", suppress=["agent_framework"])

logger.info("[bold green]═══ Microsoft Agent Framework loaded ═══[/bold green]")


# Wrap search_wikipedia with tracking for Microsoft Agent Framework
@track(type="tool")
def search_wikipedia_tracked(query: str) -> list[str]:
    """Search Wikipedia for information about a topic."""
    with logger.log_tool("search_wikipedia", query):
        return search_wikipedia(query, use_api=True)


class MicrosoftAgentFrameworkAgent(OptimizableAgent):
    """Wraps a Microsoft Agent Framework chat agent for Opik optimizers."""

    project_name = "microsoft-agent-framework"
    default_model_id = "gpt-4o-mini"

    def __init__(
        self, prompt: ChatPrompt | None = None, project_name: str | None = None
    ) -> None:
        try:
            if prompt is not None:
                super().__init__(prompt, project_name)
            else:
                pass  # Will be initialized later
        except Exception as e:
            logger.agent_error(e, include_traceback=True)
            raise

    def _resolve_system_text(self, prompt: ChatPrompt) -> str:
        """Extract system message from prompt."""
        system_text = ""
        for message in prompt.get_messages():
            if message.get("role") == "system":
                system_text = message.get("content", "")
                break
        return system_text

    def _extract_latest_user_message(self, messages: list[dict[str, str]]) -> str:
        """Extract the latest user message from the messages list."""
        for message in reversed(messages):
            if message.get("role") == "user":
                return message.get("content", "")
        return ""

    def init_agent(self, prompt: ChatPrompt) -> None:
        self.prompt = prompt
        system_text = self._resolve_system_text(prompt)
        model_id = prompt.model or self.default_model_id

        logger.agent_init(model=model_id, tools=["search_wikipedia"])

        self.function_map = prompt.function_map or {}

        # Create the client
        client = OpenAIChatClient(model_id=model_id)

        # Create agent with explicit tool definitions
        self.agent = client.create_agent(
            name=self.project_name,
            instructions=system_text,
            tools=[search_wikipedia_tracked],
        )

    def invoke(self, messages: list[dict[str, str]], seed: int | None = None) -> str:
        question = self._extract_latest_user_message(messages)

        async def _run_agent() -> Any:
            result = await self.agent.run(question)
            return result

        with logger.log_invoke(question) as ctx:
            result = asyncio.run(_run_agent())
            final_str = str(result)
            ctx["response"] = final_str
            return final_str
