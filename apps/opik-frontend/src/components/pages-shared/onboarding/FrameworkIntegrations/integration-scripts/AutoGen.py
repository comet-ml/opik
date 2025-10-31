import asyncio
import os

import opik  # HIGHLIGHTED_LINE
from autogen_agentchat.agents import AssistantAgent
from autogen_agentchat.ui import Console
from autogen_ext.models.openai import OpenAIChatCompletionClient
from opentelemetry import trace
from opentelemetry.exporter.otlp.proto.http.trace_exporter import OTLPSpanExporter
from opentelemetry.instrumentation.openai import OpenAIInstrumentor
from opentelemetry.sdk.resources import Resource
from opentelemetry.sdk.trace import TracerProvider
from opentelemetry.sdk.trace.export import BatchSpanProcessor

opik.configure()  # HIGHLIGHTED_LINE

# Configure Opik
opik_config = opik.config.get_from_user_inputs()  # HIGHLIGHTED_LINE

# Set OpenTelemetry environment variables
os.environ["OTEL_EXPORTER_OTLP_ENDPOINT"] = (  # HIGHLIGHTED_LINE
    "https://www.comet.com/opik/api/v1/private/otel"  # HIGHLIGHTED_LINE
)  # HIGHLIGHTED_LINE

headers = (  # HIGHLIGHTED_LINE
    f"Authorization={opik_config.api_key},"  # HIGHLIGHTED_LINE
    f"projectName={opik_config.project_name},"  # HIGHLIGHTED_LINE
    f"Comet-Workspace={opik_config.workspace}"  # HIGHLIGHTED_LINE
)  # HIGHLIGHTED_LINE
os.environ["OTEL_EXPORTER_OTLP_HEADERS"] = headers  # HIGHLIGHTED_LINE


def setup_telemetry():
    """Configure OpenTelemetry with Opik"""
    resource = Resource.create(
        {
            "service.name": "autogen-demo",
            "service.version": "1.0.0",
            "deployment.environment": "development",
        }
    )

    provider = TracerProvider(resource=resource)
    processor = BatchSpanProcessor(OTLPSpanExporter())
    provider.add_span_processor(processor)
    trace.set_tracer_provider(provider)

    # Instrument OpenAI calls
    OpenAIInstrumentor().instrument()


# Define model client
model_client = OpenAIChatCompletionClient(
    model="gpt-4o",
    # api_key="YOUR_API_KEY",
)


# Define a simple function tool
async def get_weather(city: str) -> str:
    """Get the weather for a given city."""
    return f"The weather in {city} is 73 degrees and Sunny."


# Define AssistantAgent
agent = AssistantAgent(
    name="weather_agent",
    model_client=model_client,
    tools=[get_weather],
    system_message="You are a helpful assistant.",
    reflect_on_tool_use=True,
    model_client_stream=True,
)


async def main() -> None:
    tracer = trace.get_tracer(__name__)
    with tracer.start_as_current_span("agent_conversation") as span:
        task = "What is the weather in New York?"

        span.set_attribute("input", task)
        res = await Console(agent.run_stream(task=task))
        span.set_attribute("output", res.messages[-1].content)

        await model_client.close()


if __name__ == "__main__":
    setup_telemetry()
    asyncio.run(main())
