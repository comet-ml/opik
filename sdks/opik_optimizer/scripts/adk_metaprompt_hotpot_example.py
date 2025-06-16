from typing import Any, Dict, Optional

from opik_optimizer import OptimizableAgent, ChatPrompt, MetaPromptOptimizer
from opik_optimizer.datasets import hotpot_300

from opik.evaluation.metrics import LevenshteinRatio
from opik.integrations.adk import OpikTracer
from opik.evaluation.metrics.score_result import ScoreResult

import json
import asyncio

from google.adk.agents import LlmAgent
from google.adk.models.lite_llm import LiteLlm
from google.adk.runners import Runner
from google.adk.sessions import InMemorySessionService
from google.genai import types
from pydantic import BaseModel, Field


def levenshtein_ratio(dataset_item: Dict[str, Any], llm_output: str) -> ScoreResult:
    metric = LevenshteinRatio()
    return metric.score(reference=dataset_item["answer"], output=llm_output)


# --- 1. Define Constants ---
APP_NAME = "agent_comparison_app"
USER_ID = "test_user_456"
SESSION_ID_TOOL_AGENT = "session_tool_agent_xyz"
SESSION_ID_SCHEMA_AGENT = "session_schema_agent_xyz"
MODEL = LiteLlm(model="openai/gpt-4.1")

# Tools:
import dspy


def search_wikipedia(query: str) -> list[str]:
    """
    This agent is used to search wikipedia. It can retrieve additional details
    about a topic.
    """
    results = dspy.ColBERTv2(url="http://20.102.90.50:2017/wiki17_abstracts")(
        query, k=1
    )
    return results[0]["text"]


# Input schema used by both agents
class SearchInput(BaseModel):
    query: str = Field(description="The query to use in the search.")


dataset = hotpot_300()


class ADKAgent(OptimizableAgent):
    model = "openai/gpt-4.1"
    project_name = "adk-agent-wikipedia"
    input_dataset_field = "question"

    def init_agent(self, agent_config: Dict[str, Any]) -> None:
        prompt: ChatPrompt = agent_config["chat-prompt"].get_system_prompt()

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
        self, query_json: Dict[str, Any], seed: Optional[int] = None
    ) -> Dict[str, Any]:
        query_json = {self.input_dataset_field: query_json[self.input_dataset_field]}
        query_json = json.dumps(query_json)
        session_service = InMemorySessionService()
        # Create separate sessions for clarity, though not strictly necessary if context is managed
        session_service.create_session(
            app_name=APP_NAME, user_id=USER_ID, session_id=SESSION_ID_TOOL_AGENT
        )
        # Create a runner for EACH agent
        runner = Runner(
            agent=self.agent, app_name=APP_NAME, session_service=session_service
        )

        async def _invoke():
            user_content = types.Content(
                role="user", parts=[types.Part(text=query_json)]
            )

            final_response_content = "No final response received."
            try:
                async for event in runner.run_async(
                    user_id=USER_ID,
                    session_id=SESSION_ID_TOOL_AGENT,
                    new_message=user_content,
                ):
                    # print(f"Event: {event.type}, Author: {event.author}") # Uncomment for detailed logging
                    if (
                        event.is_final_response()
                        and event.content
                        and event.content.parts
                    ):
                        # For output_schema, the content is the JSON string itself
                        final_response_content = event.content.parts[0].text
            except Exception:
                final_response_content = "Error"

            current_session = session_service.get_session(
                app_name=APP_NAME,
                user_id=USER_ID,
                session_id=SESSION_ID_TOOL_AGENT,
            )
            stored_output = current_session.state.get(self.agent.output_key)

            return stored_output

        return asyncio.run(_invoke())


prompt = """
You are a helpful assistant. Use the `search_wikipedia` tool to find factual information when appropriate.
The user will provide a question string like "Who is Barack Obama?".
1. Extract the item to look up
2. Use the `search_wikipedia` tool to find details
3. Respond clearly to the user, stating the answer found by the tool.
"""

agent_config = {"chat-prompt": ChatPrompt(system=prompt)}

# Test it:
agent = ADKAgent(agent_config)
result = agent.invoke_dataset_item(
    {"question": "Which is heavier: a newborn elephant, or a motor boat?"}
)
print(result)

# Optimize it:
optimizer = MetaPromptOptimizer(
    model="openai/gpt-4o-mini",  # Using gpt-4o-mini for evaluation for speed
    max_rounds=3,  # Number of optimization rounds
    num_prompts_per_round=4,  # Number of prompts to generate per round
    improvement_threshold=0.01,  # Minimum improvement required to continue
    temperature=0.1,  # Lower temperature for more focused responses
    max_completion_tokens=5000,  # Maximum tokens for model completion
    num_threads=12,  # Number of threads for parallel evaluation
    subsample_size=10,  # Fixed subsample size of 10 items
)
result = optimizer.optimize_agent(
    agent_class=ADKAgent,
    agent_config=agent_config,
    dataset=dataset,
    metric=levenshtein_ratio,
    n_samples=10,
)
