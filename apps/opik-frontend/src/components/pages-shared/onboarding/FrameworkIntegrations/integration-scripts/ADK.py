import asyncio
import datetime
from zoneinfo import ZoneInfo

from google.adk.agents import LlmAgent
from google.adk.models.lite_llm import LiteLlm
from google.adk.runners import Runner
from google.adk.sessions import InMemorySessionService
from google.genai import types
from opik import configure  # HIGHLIGHTED_LINE
from opik.integrations.adk import OpikTracer  # HIGHLIGHTED_LINE

configure()  # HIGHLIGHTED_LINE

# os.environ["OPENAI_API_KEY"] = "your-api-key-here"


def get_weather(city: str) -> dict:
    """Get weather information for a city."""
    if city.lower() == "new york":
        return {
            "status": "success",
            "report": "The weather in New York is sunny with a temperature of 25 °C (77 °F).",
        }
    elif city.lower() == "london":
        return {
            "status": "success",
            "report": "The weather in London is cloudy with a temperature of 18 °C (64 °F).",
        }
    return {
        "status": "error",
        "error_message": f"Weather info for '{city}' is unavailable.",
    }


def get_current_time(city: str) -> dict:
    """Get current time for a city."""
    if city.lower() == "new york":
        tz = ZoneInfo("America/New_York")
        now = datetime.datetime.now(tz)
        return {
            "status": "success",
            "report": now.strftime(
                f"The current time in {city} is %Y-%m-%d %H:%M:%S %Z%z."
            ),
        }
    elif city.lower() == "london":
        tz = ZoneInfo("Europe/London")
        now = datetime.datetime.now(tz)
        return {
            "status": "success",
            "report": now.strftime(
                f"The current time in {city} is %Y-%m-%d %H:%M:%S %Z%z."
            ),
        }
    return {"status": "error", "error_message": f"No timezone info for '{city}'."}


basic_tracer = OpikTracer(  # HIGHLIGHTED_LINE
    name="basic-weather-agent",  # HIGHLIGHTED_LINE
    tags=["basic", "weather", "time", "single-agent"],  # HIGHLIGHTED_LINE
    metadata={  # HIGHLIGHTED_LINE
        "environment": "development",  # HIGHLIGHTED_LINE
        "model": "gpt-4o",  # HIGHLIGHTED_LINE
        "framework": "google-adk",  # HIGHLIGHTED_LINE
        "example": "basic",  # HIGHLIGHTED_LINE
    },  # HIGHLIGHTED_LINE
    project_name="adk-basic-demo",  # HIGHLIGHTED_LINE
)  # HIGHLIGHTED_LINE

# Initialize LiteLLM with OpenAI gpt-4o
llm = LiteLlm(model="openai/gpt-4o")

# Create the basic agent with Opik callbacks
basic_agent = LlmAgent(
    name="weather_time_agent",
    model=llm,
    description="Agent for answering time & weather questions",
    instruction="Answer questions about the time or weather in a city. Be helpful and provide clear information.",
    tools=[get_weather, get_current_time],
    before_agent_callback=basic_tracer.before_agent_callback,  # HIGHLIGHTED_LINE
    after_agent_callback=basic_tracer.after_agent_callback,  # HIGHLIGHTED_LINE
    before_model_callback=basic_tracer.before_model_callback,  # HIGHLIGHTED_LINE
    after_model_callback=basic_tracer.after_model_callback,  # HIGHLIGHTED_LINE
    before_tool_callback=basic_tracer.before_tool_callback,  # HIGHLIGHTED_LINE
    after_tool_callback=basic_tracer.after_tool_callback,  # HIGHLIGHTED_LINE
)

basic_session_service = InMemorySessionService()
basic_runner = Runner(
    agent=basic_agent,
    app_name="basic_weather_app",
    session_service=basic_session_service,
)


async def setup_basic_session():
    """Create a new session for the basic example."""
    sess = await basic_session_service.create_session(
        app_name="basic_weather_app",
        user_id="user_basic",
        session_id="session_basic_001",
    )
    return sess.id


async def call_basic_agent(user_msg: str, session_id: str):
    """Send a message to the basic agent and get the response."""
    print(f"User: {user_msg}")
    content = types.Content(role="user", parts=[types.Part(text=user_msg)])
    async for event in basic_runner.run_async(
        user_id="user_basic", session_id=session_id, new_message=content
    ):
        if event.is_final_response():
            print(f"Assistant: {event.content.parts[0].text}")
            print()


async def main():
    # Create a session for basic example
    basic_session_id = await setup_basic_session()
    print(f"Created basic session: {basic_session_id}")
    print()

    # Test weather query
    await call_basic_agent("What's the weather like in New York?", basic_session_id)

    # Test time query
    await call_basic_agent("What time is it in London?", basic_session_id)

    # Test combined query
    await call_basic_agent(
        "Can you tell me both the weather and time in New York?", basic_session_id
    )


if __name__ == "__main__":
    asyncio.run(main())
