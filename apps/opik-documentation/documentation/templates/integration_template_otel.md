---
description: How to send [FRAMEWORK_NAME] telemetry data to Opik using OpenTelemetry
---

# [FRAMEWORK_NAME] Integration via OpenTelemetry

Opik offers an [OpenTelemetry backend](https://www.comet.com/docs/opik/tracing/opentelemetry/overview) that ingests trace data from a variety of OpenTelemetry instrumentation libraries. This guide demonstrates how to configure [FRAMEWORK_NAME] to send telemetry data to Opik.

> **About [FRAMEWORK_NAME]:** [Brief description of the framework and its telemetry capabilities]

## Prerequisites

Before you begin, ensure you have:
1. An Opik account ([create one for free](https://www.comet.com/signup?from=llm&utm_source=opik&utm_medium=docs&utm_content=[framework_name]&utm_campaign=opik))
2. [FRAMEWORK_NAME] installed and working
3. [Any framework-specific requirements]

## Step 1: Install Dependencies

Install the required OpenTelemetry packages:

```python
%pip install [framework_otel_packages] opentelemetry-api opentelemetry-sdk opentelemetry-exporter-otlp
```

## Step 2: Configure Environment Variables

Set up the required environment variables to forward trace data to Opik:

### For Opik Cloud (Comet.com)

```python
import os
import getpass

# Opik configuration
OPIK_API_KEY = None
OPIK_PROJECT_NAME = "[framework_name]-integration"
OPIK_WORKSPACE = "your-workspace-name"

if OPIK_API_KEY is None and "OPIK_API_KEY" not in os.environ:
    OPIK_API_KEY = getpass.getpass("Enter your OPIK API key: ")
elif OPIK_API_KEY is None:
    OPIK_API_KEY = os.environ["OPIK_API_KEY"]

# Set OpenTelemetry environment variables
os.environ["OTEL_EXPORTER_OTLP_ENDPOINT"] = "https://www.comet.com/opik/api/v1/private/otel"

# Build headers for authentication
headers = [f"Authorization={OPIK_API_KEY}"]
if OPIK_PROJECT_NAME:
    headers.append(f"projectName={OPIK_PROJECT_NAME}")
if OPIK_WORKSPACE:
    headers.append(f"Comet-Workspace={OPIK_WORKSPACE}")

os.environ["OTEL_EXPORTER_OTLP_HEADERS"] = ",".join(headers)
```

### For Self-hosted Opik

```python
import os

# For self-hosted deployment
os.environ["OTEL_EXPORTER_OTLP_ENDPOINT"] = "http://localhost:5173/api/v1/private/otel"
os.environ["OTEL_EXPORTER_OTLP_HEADERS"] = f"projectName={OPIK_PROJECT_NAME}"
```

## Step 3: Configure OpenTelemetry SDK

Set up the OpenTelemetry SDK to export traces to Opik:

```python
from opentelemetry import trace
from opentelemetry.exporter.otlp.proto.http.trace_exporter import OTLPSpanExporter
from opentelemetry.sdk.trace import TracerProvider
from opentelemetry.sdk.trace.export import BatchSpanProcessor
from opentelemetry.sdk.resources import Resource
from opentelemetry.semconv.resource import ResourceAttributes

# Configure the tracer provider
resource = Resource.create({
    ResourceAttributes.SERVICE_NAME: "[framework_name]-app"
})

provider = TracerProvider(resource=resource)
processor = BatchSpanProcessor(
    OTLPSpanExporter(
        endpoint=os.environ["OTEL_EXPORTER_OTLP_ENDPOINT"] + "/v1/traces",
        headers=dict(header.split("=", 1) for header in os.environ["OTEL_EXPORTER_OTLP_HEADERS"].split(","))
    )
)
provider.add_span_processor(processor)
trace.set_tracer_provider(provider)
```

## Step 4: Configure [FRAMEWORK_NAME] Instrumentation

Configure [FRAMEWORK_NAME] to use OpenTelemetry:

```python
# Framework-specific configuration
[framework_specific_configuration_code]
```

## Step 5: Verify Integration

Run a simple test to verify the integration is working:

```python
# Example application code
[example_application_code]

# The traces should now appear in your Opik dashboard
```

## Alternative: Environment Variable Configuration

For production deployments, you can configure everything using environment variables:

```bash
# Opik Cloud
export OTEL_EXPORTER_OTLP_ENDPOINT="https://www.comet.com/opik/api/v1/private/otel"
export OTEL_EXPORTER_OTLP_HEADERS="Authorization=<your-api-key>,projectName=<project-name>,Comet-Workspace=<workspace>"

# Self-hosted
export OTEL_EXPORTER_OTLP_ENDPOINT="http://localhost:5173/api/v1/private/otel"
export OTEL_EXPORTER_OTLP_HEADERS="projectName=<project-name>"

# OpenTelemetry protocol
export OTEL_EXPORTER_OTLP_PROTOCOL="http/protobuf"
```

## [Framework-Specific Configuration]

### [Specific Configuration Options]

[Add any framework-specific configuration details, such as:
- Specific instrumentation libraries
- Framework-specific environment variables
- Custom span attributes
- Sampling configuration
- Service naming conventions
- etc.]

## Viewing Results

Once configured, your traces will automatically appear in the Opik dashboard:

![Integration Screenshot](https://path/to/screenshot.png)

## Troubleshooting

### Common Issues

1. **No traces appearing**: 
   - Verify your API key and endpoint configuration
   - Check that the OTEL_EXPORTER_OTLP_HEADERS are properly formatted
   - Ensure [FRAMEWORK_NAME] is generating telemetry data

2. **Authentication errors**:
   - Double-check your API key is correct
   - Verify your workspace name matches your Comet account

3. **Connection errors**:
   - For self-hosted: Ensure Opik is running and accessible
   - For cloud: Check your internet connection

### Debug Mode

Enable debug logging to troubleshoot issues:

```python
import logging
logging.basicConfig(level=logging.DEBUG)
```

### Manual Testing

Test the OTEL export manually:

```python
# Manual span creation for testing
tracer = trace.get_tracer(__name__)
with tracer.start_as_current_span("test-span") as span:
    span.set_attribute("test.attribute", "test-value")
    span.add_event("Test event")
    # This should appear in Opik
```

## Getting Help

If you encounter issues:
- Check the [OpenTelemetry documentation](https://opentelemetry.io/docs/)
- Review the [Opik OpenTelemetry guide](https://www.comet.com/docs/opik/tracing/opentelemetry/overview)
- Join our [Slack community](https://chat.comet.com)
- Report issues on [GitHub](https://github.com/comet-ml/opik/issues) 