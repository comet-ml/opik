from __future__ import annotations

import sys
from pathlib import Path

from opik_optimizer import (
    OptimizableAgent,
    ChatPrompt,
)
from opik_optimizer.utils import search_wikipedia
from opik_optimizer.utils.llm_logger import LLMLogger
from opik import track

# Setup logger
logger = LLMLogger("pydantic_ai", agent_name="Pydantic AI")

logger.info("[bold green]═══ Pydantic AI loaded ═══[/bold green]")


def _ensure_local_pydantic_ai() -> None:
    """Attempt to load the local optimizer venv when running outside it."""
    script_dir = Path(__file__).resolve()
    candidate_envs = [
        script_dir.parents[3] / ".venv",  # sdks/opik_optimizer/.venv
        script_dir.parents[5] / ".venv",  # repo-level .venv (if present)
    ]
    for env_path in candidate_envs:
        site_dir = (
            env_path
            / "lib"
            / f"python{sys.version_info.major}.{sys.version_info.minor}"
            / "site-packages"
        )
        if site_dir.exists() and str(site_dir) not in sys.path:
            sys.path.insert(0, str(site_dir))


try:
    from pydantic_ai import Agent
    from pydantic_ai.tools import RunContext
    from pydantic_ai.messages import ModelRequest, UserPromptPart, SystemPromptPart
except ImportError:  # pragma: no cover - falls back when script run outside venv
    _ensure_local_pydantic_ai()
    from pydantic_ai import Agent
    from pydantic_ai.tools import RunContext
    from pydantic_ai.messages import ModelRequest, UserPromptPart, SystemPromptPart


@track(type="tool")
def search_wikipedia_tool(ctx: RunContext, query: str) -> list[str]:
    """
    Search Wikipedia for information about a topic. Returns relevant article abstracts.
    """
    with logger.log_tool("search_wikipedia", query):
        return search_wikipedia(query, use_api=True)


class PydanticAIAgent(OptimizableAgent):
    """Agent using Pydantic AI for optimization."""

    project_name: str = "pydantic-ai-agent"
    default_model: str = "openai:gpt-4o-mini"

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
        for message in prompt.get_messages():
            if message.get("role") == "system":
                return message.get("content", "")
        return ""

    def _extract_latest_user_message(self, messages: list[dict[str, str]]) -> str:
        """Extract the latest user message from the messages list."""
        for message in reversed(messages):
            if message.get("role") == "user":
                return message.get("content", "")
        return ""

    def init_agent(self, prompt: ChatPrompt) -> None:
        """Initialize the agent with the provided configuration."""
        self.prompt = prompt
        system_text = self._resolve_system_text(prompt)
        model_name = prompt.model or self.default_model

        logger.agent_init(model=model_name, tools=["search_wikipedia"])

        try:
            self.agent = Agent(
                model_name,
                output_type=str,
                system_prompt=system_text,
            )
            # Register the tool - note: tool decorator must be applied BEFORE track decorator
            self.agent.tool(search_wikipedia_tool)
        except Exception as e:
            logger.agent_error(e, include_traceback=True)
            raise

    def invoke(self, messages: list[dict[str, str]], seed: int | None = None) -> str:
        user_prompt = self._extract_latest_user_message(messages)

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

        with logger.log_invoke(user_prompt) as ctx:
            result = self.agent.run_sync(
                user_prompt=user_prompt,
                message_history=message_history if message_history else None,
            )
            response = result.output
            ctx["response"] = response  # Log response
            return response
