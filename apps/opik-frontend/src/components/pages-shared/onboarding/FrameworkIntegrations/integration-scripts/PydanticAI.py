import os

import logfire
import opik  # HIGHLIGHTED_LINE
from pydantic_ai import Agent

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

# Configure logfire to send traces to Opik
logfire.configure(
    send_to_logfire=False,
)
logfire.instrument_httpx(capture_all=True)

# Create a simple Pydantic AI agent
agent = Agent(
    "openai:gpt-4",
    system_prompt="You are a helpful assistant.",
)

# Run the agent - this will be automatically traced to Opik
result = agent.run_sync("Tell me a joke")
print(result.data)
