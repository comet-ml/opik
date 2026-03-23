import os

import opik  # HIGHLIGHTED_LINE
from agno.agent import Agent
from agno.models.openai import OpenAIChat
from agno.tools.yfinance import YFinanceTools
from openinference.instrumentation.agno import AgnoInstrumentor
from opentelemetry import trace as trace_api
from opentelemetry.exporter.otlp.proto.http.trace_exporter import OTLPSpanExporter
from opentelemetry.sdk.trace import TracerProvider
from opentelemetry.sdk.trace.export import SimpleSpanProcessor

opik.configure()  # HIGHLIGHTED_LINE

# os.environ["OPENAI_API_KEY"] = "your-api-key-here"

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

# Configure the tracer provider
tracer_provider = TracerProvider()
tracer_provider.add_span_processor(SimpleSpanProcessor(OTLPSpanExporter()))
trace_api.set_tracer_provider(tracer_provider=tracer_provider)

# Start instrumenting agno
AgnoInstrumentor().instrument()

# Create and configure the agent
agent = Agent(
    name="Stock Price Agent",
    model=OpenAIChat(id="gpt-4o-mini"),
    tools=[YFinanceTools()],
    instructions="You are a stock price agent. Answer questions in the style of a stock analyst.",
    debug_mode=True,
)

# Use the agent
agent.print_response("What is the current price of Apple?")
