from typing import Optional, Any, List, Dict

from opik_optimizer import (
    OptimizableAgent,
    ChatPrompt,
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


# Create an ADK agent:
def create_agent(project_name: str) -> Any:
    opik_tracer = OpikTracer(project_name)
    return LlmAgent(
        model=LiteLlm(model="openai/gpt-4.1"),
        name="wikipedia_agent_tool",
        description="Retrieves wikipedia information using a specific tool.",
        instruction="",  # We'll use the chat-prompt in this example
        tools=[search_wikipedia],
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

    def init_agent(self, prompt: ChatPrompt) -> None:
        self.prompt = prompt
        self.agent = create_agent(self.project_name)

    def invoke_dataset_item(self, dataset_item: Dict[str, str]) -> str:
        messages = self.prompt.get_messages(dataset_item)
        return self.invoke(messages)

    def invoke(self, messages: List[Dict[str, str]], seed: Optional[int] = None) -> str:
        APP_NAME = "agent_comparison_app"
        USER_ID = "test_user_456"
        SESSION_ID_TOOL_AGENT = "session_tool_agent_xyz"

        session_service = InMemorySessionService()
        # Create separate sessions for clarity, though not strictly necessary if context is managed
        session_service.create_session(
            app_name=APP_NAME, user_id=USER_ID, session_id=SESSION_ID_TOOL_AGENT
        )
        # Create a runner for EACH agent
        runner = Runner(
            agent=self.agent, app_name=APP_NAME, session_service=session_service
        )

        final_response_content = "No response received from agent."
        for message in messages:
            adk_message = types.Content(
                role=message["role"], parts=[types.Part(text=message["content"])]
            )
            for event in runner.run(
                user_id=USER_ID,
                session_id=SESSION_ID_TOOL_AGENT,
                new_message=adk_message,
            ):
                # print(f"Event: {event}") # Uncomment for detailed logging
                if event.is_final_response() and event.content and event.content.parts:
                    # For output_schema, the content is the JSON string itself
                    final_response_content = event.content.parts[0].text

        return final_response_content
