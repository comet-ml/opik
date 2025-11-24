from typing import Any
import os

from opik_optimizer import (
    OptimizableAgent,
    ChatPrompt,
)
from opik_optimizer.utils import search_wikipedia
from opik_optimizer.utils.llm_logger import LLMLogger
from opik.integrations.adk import OpikTracer
from opik import track

from google.adk.agents import LlmAgent
from google.adk.models.lite_llm import LiteLlm
from google.adk.runners import Runner
from google.adk.sessions import InMemorySessionService
from google.genai import types

from pydantic import BaseModel, Field

ADK_APP_NAME = os.environ.get("ADK_APP_NAME", "agents")
ADK_USER_ID = os.environ.get("ADK_USER_ID", "test_user_456")

# Setup logger
logger = LLMLogger("adk", agent_name="ADK")

logger.info("[bold green]═══ ADK Agent loaded ═══[/bold green]")


# Create a wrapper without default parameters for ADK compatibility
def search_wikipedia_adk(query: str) -> list[str]:
    """
    This agent is used to search wikipedia. It can retrieve additional details
    about a topic.
    """
    with logger.log_tool("search_wikipedia", query):
        return search_wikipedia(query, use_api=True)


# Input schema used by both agents
class SearchInput(BaseModel):
    query: str = Field(description="The query to use in the search.")


# Create an ADK agent:
def create_agent(project_name: str) -> Any:
    opik_tracer = OpikTracer(project_name)
    return LlmAgent(
        model=LiteLlm(model="openai/gpt-4.1"),
        name="wikipedia_agent_tool",
        description="Retrieves wikipedia information using a specific tool.",
        instruction="",  # We'll use the chat-prompt in this example
        tools=[track(type="tool")(search_wikipedia_adk)],
        input_schema=SearchInput,
        output_key="wikipedia_tool_result",  # Store final text response
        before_agent_callback=opik_tracer.before_agent_callback,
        after_agent_callback=opik_tracer.after_agent_callback,
        before_model_callback=opik_tracer.before_model_callback,
        after_model_callback=opik_tracer.after_model_callback,
        before_tool_callback=opik_tracer.before_tool_callback,
        after_tool_callback=opik_tracer.after_tool_callback,
    )


# Wrap it in an OptimizableAgent
class ADKAgent(OptimizableAgent):
    project_name = "adk-agent"

    def _resolve_system_text(self, prompt: ChatPrompt) -> str:
        """Extract system message from prompt."""
        messages = prompt.get_messages()
        for message in messages:
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
        self.prompt = prompt
        # ADK uses a fixed model in create_agent, but we log the prompt model if available
        model_name = prompt.model or "openai/gpt-4.1"

        logger.agent_init(model=model_name, tools=["search_wikipedia"])

        self.agent = create_agent(self.project_name)

    def invoke_dataset_item(self, dataset_item: dict[str, str]) -> str:
        messages = self.prompt.get_messages(dataset_item)
        return self.invoke(messages)

    def invoke(self, messages: list[dict[str, str]], seed: int | None = None) -> str:
        import asyncio

        question = self._extract_latest_user_message(messages)

        session_service = InMemorySessionService()
        # Create a runner for EACH agent
        runner = Runner(
            agent=self.agent, app_name=ADK_APP_NAME, session_service=session_service
        )

        async def _invoke_async() -> str:
            # Create separate sessions for clarity, though not strictly necessary if context is managed
            session = await session_service.create_session(
                app_name=ADK_APP_NAME, user_id=ADK_USER_ID
            )
            final_response_content = "No response received from agent."
            for message in messages:
                adk_message = types.Content(
                    role=message["role"], parts=[types.Part(text=message["content"])]
                )
                async for event in runner.run_async(
                    user_id=ADK_USER_ID, session_id=session.id, new_message=adk_message
                ):
                    if (
                        event.is_final_response()
                        and event.content
                        and event.content.parts
                    ):
                        # For output_schema, the content is the JSON string itself
                        final_response_content = event.content.parts[0].text
            return final_response_content

        with logger.log_invoke(question) as ctx:
            response = asyncio.run(_invoke_async())
            ctx["response"] = response
            return response
