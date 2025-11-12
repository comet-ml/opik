from typing import Any, Callable

from google.adk.agents import LlmAgent
from google.adk.models.lite_llm import LiteLlm
from google.adk.runners import Runner
from google.adk.sessions import InMemorySessionService
from google.genai import types
from opik import track
from opik.evaluation.metrics import LevenshteinRatio
from opik.evaluation.metrics.score_result import ScoreResult
from opik.integrations.adk import OpikTracer
from opik_optimizer import ChatPrompt, EvolutionaryOptimizer, OptimizableAgent
from opik_optimizer.datasets import hotpot_300
from opik_optimizer.utils import search_wikipedia
from pydantic import BaseModel, Field
from functools import update_wrapper

from adk_agent import ADKAgent

dataset = hotpot_300()

OPTIMIZABLE_STATUS = None
OPTIMIZABLE_PARAMETERS = []
OPTIMIZABLE_PARAMETERS_VALUES = {}


def get_trial_value(id: str, default: str, type: str) -> str:
    if OPTIMIZABLE_STATUS == "collect":
        OPTIMIZABLE_PARAMETERS.append({"id": id, "type": type, "default": default})
    elif OPTIMIZABLE_STATUS == "optimize":
        return OPTIMIZABLE_PARAMETERS_VALUES[id]
    return None

def optimize_system_prompt(prompt: str, prompt_id: str) -> str:
    # If no new prompt is available or no optimization is running, returns the original prompt
    new_prompt = get_trial_value(prompt_id, default=prompt, type="system")
    if not new_prompt:
        return prompt
    return new_prompt


def optimize_adk_function_tool(f: Callable) -> Any:
    default_doc = f.__doc__

    def wrapper(*args: Any, **kwargs: Any) -> Any:
        return f(*args, **kwargs)
    
    update_wrapper(wrapper, f)

    # Update the docstring with the new value
    new_doc = get_trial_value("doc", default=default_doc, type="tool_description")
    if not new_doc:
        new_doc = default_doc

    wrapper.__doc__ = new_doc

    return wrapper


# Create a wrapper without default parameters for ADK compatibility
def search_wikipedia_adk(query: str) -> list[str]:
    """
    This agent is used to search wikipedia. It can retrieve additional details
    about a topic.
    """
    return search_wikipedia(query, use_api=False)


# Input schema used by both agents
class SearchInput(BaseModel):
    query: str = Field(description="The query to use in the search.")


system_prompt = """
You are a helpful assistant. Use the `search_wikipedia` tool to find factual information when appropriate.
The user will provide a question string like "Who is Barack Obama?".
1. Extract the item to look up
2. Use the `search_wikipedia` tool to find details
3. Respond clearly to the user, stating the answer found by the tool.
"""

# Create an ADK agent:
def create_agent(opik_tracer: OpikTracer) -> Any:
    return LlmAgent(
        model=LiteLlm(model="openai/gpt-4.1"),
        name="wikipedia_agent_tool",
        description="Retrieves wikipedia information using a specific tool.",
        instruction=optimize_system_prompt(system_prompt, "system"),  # We'll use the chat-prompt in this example
        tools=[track(type="tool")(optimize_adk_function_tool(search_wikipedia_adk))],
        input_schema=SearchInput,
        output_key="wikipedia_tool_result",  # Store final text response
        before_agent_callback=opik_tracer.before_agent_callback,
        after_agent_callback=opik_tracer.after_agent_callback,
        before_model_callback=opik_tracer.before_model_callback,
        after_model_callback=opik_tracer.after_model_callback,
        before_tool_callback=opik_tracer.before_tool_callback,
        after_tool_callback=opik_tracer.after_tool_callback,
    )


def levenshtein_ratio(dataset_item: dict[str, Any], llm_output: str) -> ScoreResult:
    metric = LevenshteinRatio()
    return metric.score(reference=dataset_item["answer"], output=llm_output)



def get_optimizable_parameters(get_agent: Any, opik_tracer: OpikTracer) -> list[str]:
    global OPTIMIZABLE_STATUS
    global OPTIMIZABLE_PARAMETERS
    # Start the collection of optimizable parameters
    OPTIMIZABLE_STATUS = "collect"

    # Call the agent to collect the optimizable parameters
    _ = get_agent(opik_tracer)

    parameters = OPTIMIZABLE_PARAMETERS
    OPTIMIZABLE_PARAMETERS = []
    OPTIMIZABLE_STATUS = None

    return parameters


def optimize_adk_agent(get_agent: Any) -> OptimizableAgent:
    # Step 1, collect all the optimizable parameters
    opik_tracer = OpikTracer(project_name="adk-agent")
    optimizable_parameters = get_optimizable_parameters(get_agent, opik_tracer)

    # Create the ChatPrompt with the optimizable parameters
    system_prompt = [x["default"] for x in optimizable_parameters if x["type"] == "system"]
    assert len(system_prompt) == 1, "Only one system prompt is supported"

    # Gather the tool descriptions
    tool_descriptions = [x["default"] for x in optimizable_parameters if x["type"] == "tool_description"]
    assert len(tool_descriptions) == 1, "Only one tool description is supported"

    # TODO: Add the tool descriptions to the ChatPrompt

    chat_prompt = ChatPrompt(
        system=system_prompt[0],
        user="{question}",
    )


    # Wrap it in an OptimizableAgent
    class ADKAgentWithOptimizableParameters(OptimizableAgent):
        project_name = "adk-agent"


        def __init__(self, *args: Any, **kwargs: Any) -> None:
            super().__init__(*args, **kwargs)

        def init_agent(self, prompt: ChatPrompt) -> None:
            nonlocal get_agent
            nonlocal opik_tracer

            self.prompt = prompt
            self.get_agent = get_agent
            self.tracker = opik_tracer

        def invoke(self, messages: list[dict[str, str]], seed: int | None = None) -> str:
            # Extract the new system prompt
            new_system_prompt = messages[0]["content"]

            OPIMIZABLE_STATUS = "optimize"
            OPTIMIZABLE_PARAMETERS_VALUES = {
                "system": new_system_prompt,
            }

            # Get the agent from the function
            agent = self.get_agent(self.tracker)
            import asyncio

            APP_NAME = "agent_comparison_app"
            USER_ID = "test_user_456"

            session_service = InMemorySessionService()
            # Create a runner for EACH agent
            runner = Runner(
                agent=agent, app_name=APP_NAME, session_service=session_service
            )

            async def _invoke_async() -> str:
                # Create separate sessions for clarity, though not strictly necessary if context is managed
                session = await session_service.create_session(
                    app_name=APP_NAME, user_id=USER_ID
                )
                final_response_content = "No response received from agent."
                for message in messages:
                    adk_message = types.Content(
                        role=message["role"], parts=[types.Part(text=message["content"])]
                    )
                    async for event in runner.run_async(
                        user_id=USER_ID,
                        session_id=session.id,
                        new_message=adk_message,
                    ):
                        # print(f"Event: {event}") # Uncomment for detailed logging
                        if (
                            event.is_final_response()
                            and event.content
                            and event.content.parts
                        ):
                            # For output_schema, the content is the JSON string itself
                            final_response_content = event.content.parts[0].text
                return final_response_content

            return asyncio.run(_invoke_async())

    return chat_prompt, ADKAgentWithOptimizableParameters



# Optimize it:
optimizer = EvolutionaryOptimizer(
    model="openai/gpt-4o-mini",
    enable_moo=False,
    enable_llm_crossover=True,
    infer_output_style=True,
    verbose=1,
    n_threads=1,
    population_size=10,
    num_generations=3,
)

prompt, agent = optimize_adk_agent(create_agent)

optimization_result = optimizer.optimize_prompt(
    prompt=prompt,
    agent_class=agent,
    dataset=dataset,
    metric=levenshtein_ratio,
    n_samples=3,
)

optimization_result.display()
