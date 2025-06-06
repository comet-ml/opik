from typing import Any, Callable, Dict, List, Literal, Optional, Tuple
from typing_extensions import TypedDict

import os
from dotenv import load_dotenv
from opik.evaluation.metrics import LevenshteinRatio
from opik_optimizer import (
    TaskConfig,
    MetricConfig,
    from_dataset_field,
    from_llm_response_text,
)
from opik_optimizer.agent_optimizer import OpikAgentOptimizer, OpikAgent
from opik_optimizer.demo import get_or_create_dataset
from opik.integrations.adk import OpikTracer

import json
import asyncio

from google.adk.agents import LlmAgent
from google.adk.models.lite_llm import LiteLlm
from google.adk.runners import Runner
from google.adk.sessions import InMemorySessionService
from google.genai import types
from pydantic import BaseModel, Field

import litellm
from litellm.integrations.opik.opik import OpikLogger

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


project_name = "adk-agent"
dataset = get_or_create_dataset("hotpot-300")

metric_config = MetricConfig(
    metric=LevenshteinRatio(project_name=project_name),
    inputs={
        "output": from_llm_response_text(),
        "reference": from_dataset_field(name="answer"),
    },
)

prompt_template = """
You are a helpful assistant. Use the `search_wikipedia` tool to find factual information when appropriate.
The user will provide a question string like "Who is Barack Obama?".
1. Extract the item to look up
2. Use the `search_wikipedia` tool to find details
3. Respond clearly to the user, stating the answer found by the tool.
"""

task_config = TaskConfig(
    instruction_prompt=prompt_template,
    input_dataset_fields=["question"],
    output_dataset_field="answer",
)


class ADKAgent(OpikAgent):
    def __init__(self, optimizer, agent_config):
        self.optimizer = optimizer
        prompt = agent_config["system-prompt"]["value"]

        # agent_tools = []
        # for key in agent_config:
        #    item = agent_config[key]
        #    if item["type"] == "tool":
        #        agent_tools.append(
        #            adk_tool(name=key, description=item["value"])(item["function"])
        #        )

        # --- 4. Configure Agents ---

        # Litellm bug requires this:
        os.environ["OPIK_PROJECT_NAME"] = project_name
        self.opik_tracer = OpikTracer(project_name)
        self.opik_logger = OpikLogger(project_name=project_name)
        litellm.callbacks = [self.opik_logger]

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

    def llm_invoke(self, prompt):
        response = litellm.completion(
            model="gpt-4o-mini", messages=[{"role": "user", "content": prompt}]
        )
        new_prompt = response.choices[0].message.content

        ## Make sure it contains necessary fields:
        # if "{input}" not in new_prompt:
        #     new_prompt += "\nQuestion: {input}"
        # if "{agent_scratchpad}" not in new_prompt:
        #     new_prompt += "\nThought: {agent_scratchpad}"
        # if "[{tool_names}]" not in new_prompt:
        #     new_prompt += "\nTool names: [{tool_names}]"
        # if "{tools}" not in new_prompt:
        #    new_prompt += "\nTools: {tools}"
        return new_prompt

    def invoke(self, query_json: Dict[str, Any]) -> Dict[str, Any]:
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

            return {"output": stored_output}

        return asyncio.run(_invoke())


optimizer = OpikAgentOptimizer(
    agent_class=ADKAgent,
    project_name=project_name,
    tags=["adk-agent"],
    task_config=task_config,
)

agent_config = {
    "chat-prompt": {"type": "chat", "value": []},
    "Wikipedia Search": {
        "type": "tool",
        "value": "Search wikipedia for abstracts. Gives a brief paragraph about a topic.",
        "function": search_wikipedia,
    },
    "system-prompt": {"type": "prompt", "value": prompt_template, "template": True},
}

agent = ADKAgent(optimizer, agent_config)
result = agent.invoke(
    {"question": "Which is heavier: a newborn elephant, or a motor boat?"}
)
print(result)

metaprompt = """Refine this prompt template to make it better.
Just give me the better prompt, nothing else.
Here is the prompt:

%r
"""

optimizer.optimize_prompt(
    agent_config=agent_config,
    dataset=dataset,
    metric_config=metric_config,
    n_samples=10,
    num_threads=16,
    metaprompt=metaprompt,
)

# TODO: get the optimizer out of Agent
# 1. self.optimizer.project_name
# 2. self.optimizer.tags
# 3. self.optimizer.task_config.input_dataset_fields
