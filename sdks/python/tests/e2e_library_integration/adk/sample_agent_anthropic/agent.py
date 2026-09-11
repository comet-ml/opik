import datetime
import os
from typing import Any
from zoneinfo import ZoneInfo

from google.adk.agents import LlmAgent
from google.adk.agents.callback_context import CallbackContext
from google.adk.models.lite_llm import LiteLlm

from opik.integrations.adk import OpikTracer

# ADK loads this file as a top-level module, so relative imports above this
# package are unavailable — keep the model id inline.
MODEL = "anthropic/claude-sonnet-4-6"


def get_weather(city: str) -> dict:
    """Retrieves the current weather report for a specified city.

    Args:
        city (str): The name of the city for which to retrieve the weather report.

    Returns:
        dict: status and result or error msg.
    """
    if city.lower() == "new york":
        return {
            "status": "success",
            "report": (
                "The weather in New York is sunny with a temperature of 25 degrees"
                " Celsius (77 degrees Fahrenheit)."
            ),
        }
    else:
        return {
            "status": "error",
            "error_message": f"Weather information for '{city}' is not available.",
        }


def get_current_time(city: str) -> dict:
    """Returns the current time in a specified city.

    Args:
        city (str): The name of the city for which to retrieve the current time.

    Returns:
        dict: status and result or error msg.
    """

    if city.lower() == "new york":
        tz_identifier = "America/New_York"
    else:
        return {
            "status": "error",
            "error_message": (f"Sorry, I don't have timezone information for {city}."),
        }

    tz = ZoneInfo(tz_identifier)
    now = datetime.datetime.now(tz)
    report = f"The current time in {city} is {now.strftime('%Y-%m-%d %H:%M:%S %Z%z')}"
    return {"status": "success", "report": report}


opik_tracer = OpikTracer()
print(
    "Project name:",
    os.environ.get("OPIK_PROJECT_NAME", "no project name set in env vars!"),
)


def after_agent_callback(
    callback_context: CallbackContext, *args: Any, **kwargs: Any
) -> None:
    opik_tracer.after_agent_callback(callback_context, *args, **kwargs)
    opik_tracer.flush()


root_agent = LlmAgent(
    name="weather_time_agent",
    model=LiteLlm(model=MODEL),
    description="Agent to answer questions about the time and weather in a city.",
    # Kept inline because ADK's AgentLoader imports each sample_agent as
    # a top-level module, so relative imports from the rest of the test
    # tree don't resolve at /run time. Keep in sync with
    # tests/library_integration/adk/agent_instructions.TOOL_USE_WEATHER_OR_TIME.
    instruction=(
        "You MUST invoke one of the provided function tools before replying — "
        "never fabricate a response, never describe a fake tool call in plain "
        "text, never paste invented JSON. If the user asks about the weather "
        "in a city, your next action MUST be a function call to "
        "`get_weather(city=...)`. If the user asks about the current time in "
        "a city, your next action MUST be a function call to "
        "`get_current_time(city=...)`. After the tool returns, write a short "
        "natural-language reply to the user that reports what the tool said. "
        "Always produce this reply even if the tool's output is already "
        "self-contained."
    ),
    tools=[get_weather, get_current_time],
    before_agent_callback=opik_tracer.before_agent_callback,
    after_agent_callback=after_agent_callback,
    before_model_callback=opik_tracer.before_model_callback,
    after_model_callback=opik_tracer.after_model_callback,
    before_tool_callback=opik_tracer.before_tool_callback,
    after_tool_callback=opik_tracer.after_tool_callback,
)
