from typing import Any, Dict, Optional

from opik_optimizer import (
    OptimizableAgent,
    ChatPrompt,
    AgentConfig,
)

from opik.integrations.adk import OpikTracer

from google.adk.agents import LlmAgent
from google.adk.models.lite_llm import LiteLlm
from google.adk.runners import Runner
from google.adk.sessions import InMemorySessionService
from google.genai import types

from pydantic import BaseModel, Field

# For wikipedia tool:
import dspy

APP_NAME = "agent_comparison_app"
USER_ID = "test_user_456"
SESSION_ID_TOOL_AGENT = "session_tool_agent_xyz"
SESSION_ID_SCHEMA_AGENT = "session_schema_agent_xyz"
MODEL = LiteLlm(model="openai/gpt-4.1")


def search_wikipedia(query: str) -> list[str]:
    """
    This agent is used to search wikipedia. It can retrieve additional details
    about a topic.
    """
    results = dspy.ColBERTv2(url="http://20.102.90.50:2017/wiki17_abstracts")(
        query, k=3
    )
    return [item["text"] for item in results]


# Input schema used by both agents
class SearchInput(BaseModel):
    query: str = Field(description="The query to use in the search.")


class ADKAgent(OptimizableAgent):
    model = "openai/gpt-4.1"
    project_name = "adk-agent-wikipedia"

    def init_agent(self, agent_config: AgentConfig) -> None:
        self.agent_config = agent_config
        prompt: ChatPrompt = agent_config.chat_prompt.get_system_prompt()

        self.opik_tracer = OpikTracer(self.project_name)

        # Agent 1: Uses a tool and output_key
        self.agent = LlmAgent(
            model=MODEL,
            name="wikipedia_agent_tool",
            description="Retrieves wikipedia information using a specific tool.",
            instruction=prompt,
            tools=[search_wikipedia],
            input_schema=SearchInput,
            output_key="wikipedia_tool_result",  # Store final text response
            before_agent_callback=self.opik_tracer.before_agent_callback,
            after_agent_callback=self.opik_tracer.after_agent_callback,
            before_model_callback=self.opik_tracer.before_model_callback,
            after_model_callback=self.opik_tracer.after_model_callback,
            before_tool_callback=self.opik_tracer.before_tool_callback,
            after_tool_callback=self.opik_tracer.after_tool_callback,
        )

    def invoke_dataset_item(
        self, dataset_item: Dict[str, Any], seed: Optional[int] = None
    ) -> str:
        all_messages = self.agent_config.chat_prompt.get_messages(dataset_item)
        # Skip the system prompt, as it is part of agent:
        messages, user_prompt = all_messages[1:-1], all_messages[-1]["content"]

        query = SearchInput(query=user_prompt)
        query_json = query.model_dump_json()
        message_history = []
        for message in messages:
            all_messages.append(
                types.Content(
                    role=message["role"], parts=[types.Part(text=message["content"])]
                )
            )
        user_content = types.Content(role="user", parts=[types.Part(text=query_json)])
        message_history.append(user_content)

        session_service = InMemorySessionService()
        # Create separate sessions for clarity, though not strictly necessary if context is managed
        session_service.create_session(
            app_name=APP_NAME, user_id=USER_ID, session_id=SESSION_ID_TOOL_AGENT
        )
        # Create a runner for EACH agent
        runner = Runner(
            agent=self.agent, app_name=APP_NAME, session_service=session_service
        )

        final_response_content = "No final response received."

        for message in message_history:
            for event in runner.run(
                user_id=USER_ID,
                session_id=SESSION_ID_TOOL_AGENT,
                new_message=user_content,
            ):
                # print(f"Event: {event.type}, Author: {event.author}") # Uncomment for detailed logging
                if event.is_final_response() and event.content and event.content.parts:
                    # For output_schema, the content is the JSON string itself
                    final_response_content = event.content.parts[0].text

        return final_response_content
