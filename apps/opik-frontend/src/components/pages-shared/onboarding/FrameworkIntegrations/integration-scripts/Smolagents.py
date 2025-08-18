import os

import opik  # HIGHLIGHTED_LINE
from openinference.instrumentation.smolagents import SmolagentsInstrumentor
from opentelemetry.exporter.otlp.proto.http.trace_exporter import OTLPSpanExporter
from opentelemetry.sdk.trace import TracerProvider
from opentelemetry.sdk.trace.export import SimpleSpanProcessor
from smolagents import CodeAgent, OpenAIServerModel, WebSearchTool

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

# Set up tracing
trace_provider = TracerProvider()
trace_provider.add_span_processor(SimpleSpanProcessor(OTLPSpanExporter()))

# Instrument SmolAgents
SmolagentsInstrumentor().instrument(tracer_provider=trace_provider)

# Create and use SmolAgents
model = OpenAIServerModel(model_id="gpt-4o")

agent = CodeAgent(tools=[WebSearchTool()], model=model, stream_outputs=True)

# Run the agent - this will be automatically traced to Opik
agent.run(
    "How many seconds would it take for a leopard at full speed to run through Pont des Arts?"
)
