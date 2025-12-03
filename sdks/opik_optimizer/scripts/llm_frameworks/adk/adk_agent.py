from __future__ import annotations

from typing import Any, TYPE_CHECKING
import os

from opik_optimizer import OptimizableAgent
from opik_optimizer.utils.tools.wikipedia import search_wikipedia
from opik.integrations.adk import OpikTracer
from opik import track

from google.adk.agents import LlmAgent
from google.adk.models.lite_llm import LiteLlm
from google.adk.runners import Runner
from google.adk.sessions import InMemorySessionService
from google.genai import types

from pydantic import BaseModel, Field

if TYPE_CHECKING:
    from opik_optimizer.api_objects import chat_prompt

ADK_APP_NAME = os.environ.get("ADK_APP_NAME", "agents")
ADK_USER_ID = os.environ.get("ADK_USER_ID", "test_user_456")


@track(type="tool")
def search_wikipedia_adk(query: str) -> list[str]:
    """Search Wikipedia for the given query."""
    return search_wikipedia(query, search_type="api")


class SearchInput(BaseModel):
    query: str = Field(description="The query to use in the search.")


class ADKAgent(OptimizableAgent):
    project_name = "adk-agent"

    def invoke_agent(
        self,
        prompts: dict[str, chat_prompt.ChatPrompt],
        dataset_item: dict[str, Any],
        allow_tool_use: bool = False,
        seed: int | None = None,
    ) -> str:
        import asyncio

        if len(prompts) > 1:
            raise ValueError("ADKAgent only supports single-prompt optimization.")

        prompt = list(prompts.values())[0]
        messages = prompt.get_messages(dataset_item)
        if not messages:
            return "No messages generated from prompt."

        # Create ADK agent
        opik_tracer = OpikTracer(self.project_name)
        agent = LlmAgent(
            model=LiteLlm(model=prompt.model or "openai/gpt-4.1"),
            name="wikipedia_agent",
            description="Retrieves wikipedia information using a specific tool.",
            instruction="",
            tools=[track(type="tool")(search_wikipedia_adk)],
            input_schema=SearchInput,
            output_key="result",
            before_agent_callback=opik_tracer.before_agent_callback,
            after_agent_callback=opik_tracer.after_agent_callback,
            before_model_callback=opik_tracer.before_model_callback,
            after_model_callback=opik_tracer.after_model_callback,
            before_tool_callback=opik_tracer.before_tool_callback,
            after_tool_callback=opik_tracer.after_tool_callback,
        )

        async def run_agent() -> str:
            session_service = InMemorySessionService()
            runner = Runner(
                agent=agent, app_name=ADK_APP_NAME, session_service=session_service
            )
            session = await session_service.create_session(
                app_name=ADK_APP_NAME, user_id=ADK_USER_ID
            )

            response = "No response received."
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
                        response = event.content.parts[0].text
            return response

        return asyncio.run(run_agent())
