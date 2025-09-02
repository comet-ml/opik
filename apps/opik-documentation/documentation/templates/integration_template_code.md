---
description: Cookbook that showcases Opik's integration with [INTEGRATION_NAME]
---

# Integration Documentation Guidelines

This document provides comprehensive guidelines for creating and maintaining integration documentation for Opik. It covers both code integrations (requiring Python SDK) and OpenTelemetry configuration-only integrations.

## 📋 Integration Type Decision Matrix

Use this matrix to determine which template to use:

| Integration Type              | Requirements                                                                                                                            | Template to Use                   | Examples                                                            |
| ----------------------------- | --------------------------------------------------------------------------------------------------------------------------------------- | --------------------------------- | ------------------------------------------------------------------- |
| **Code Integration**          | • Users install Opik Python SDK<br>• Users modify their code<br>• Uses `track_*()` wrapper functions<br>• Direct Python integration     | `integration_template_code.md`    | LangChain, CrewAI, DSPy, Haystack                                   |
| **OpenAI-Based Integration**  | • Uses OpenAI-compatible API<br>• Users install Opik Python SDK<br>• Uses `track_openai()` wrapper<br>• Compatible with OpenAI SDK      | `integration_template_openai.md`  | BytePlus, OpenRouter, Any OpenAI-compatible API                     |
| **LiteLLM Integration**       | • LLM provider supported by LiteLLM<br>• Uses OpikLogger callback<br>• Unified LiteLLM interface<br>• API key configuration required    | `integration_template_litellm.md` | OpenAI, Anthropic, Groq, Fireworks AI, Cohere, Mistral AI, xAI Grok |
| **OpenTelemetry Integration** | • Users configure OTEL endpoints<br>• No code changes required<br>• Configuration via env vars<br>• Works through OTEL instrumentations | `integration_template_otel.md`    | Ruby SDK, Pydantic AI (via Logfire), Direct OTEL Python             |

# Integration Template: Code Integration

**Use this template for**: Code integrations that require users to install Opik Python SDK and modify their code to use `track_*()` wrapper functions.

**Requirements**:

- Users install Opik Python SDK
- Users modify their code
- Uses `track_*()` wrapper functions
- Direct Python integration

**Examples**: LangChain, CrewAI, DSPy, Haystack, etc.

---

## Template Structure

[INTEGRATION_NAME]([INTEGRATION_WEBSITE_URL]) is [INTEGRATION_DESCRIPTION].

This guide explains how to integrate Opik with [INTEGRATION_NAME] using the [INTEGRATION_NAME] integration provided by Opik. By using the [INTEGRATION_NAME] integration provided by Opik, you can easily track and evaluate your [INTEGRATION_NAME] API calls within your Opik projects as Opik will automatically log the input prompt, model used, token usage, and response generated.

<!--
OPTIONAL: Include Colab notebook link only if a cookbook notebook exists.
If no notebook exists, remove this entire section.
-->

[OPTIONAL_COLAB_NOTEBOOK_SECTION]

## Getting Started

### Installation

Install the required packages:

```bash
pip install opik [integration_package]
```

### Configuring Opik

Configure Opik to send traces to your Comet project:

```python
import opik

opik.configure(
    project_name="your-project-name",
    workspace="your-workspace-name",
)
```

You can also set environment variables:

```bash
export OPIK_PROJECT_NAME="your-project-name"
export OPIK_WORKSPACE="your-workspace-name"
```

### Configuring [INTEGRATION_NAME]

[INTEGRATION_NAME_SPECIFIC_CONFIGURATION_INSTRUCTIONS]

## Usage

### Basic Usage

Set up [INTEGRATION_NAME] with Opik tracking:

```python
from opik.integrations.[integration_module] import track_[integration_name]
from [package] import [ClientClass]

# Initialize the [INTEGRATION_NAME] client
client = [ClientClass]()
tracked_client = track_[integration_name](client, project_name="optional")

# Make API calls
response = tracked_client.some_method()
```

### Using with @track decorator

Use the `@track` decorator to create comprehensive traces:

```python
from opik import track
from opik.integrations.[integration_module] import track_[integration_name]
from [package] import [ClientClass]

client = [ClientClass]()
tracked_client = track_[integration_name](client)

@track
def my_function(input_data):
    """Process data using [INTEGRATION_NAME]."""

    response = tracked_client.some_method(input_data)
    return response

# Call the tracked function
result = my_function("example input")
```

## [INTEGRATION_NAME]-Specific Features

[DESCRIBE_SPECIFIC_FEATURES_OF_THE_INTEGRATION]

## Results viewing

Once your [INTEGRATION_NAME] calls are logged with Opik, you can view them in the Opik UI. Each API call will create a trace with detailed information including:

- Input messages and parameters
- Model used and configuration
- Response content
- Token usage and cost information
- Timing and performance metrics

<!-- Include screenshot only if you have one -->
<Frame>
  <img src="/img/tracing/[integration_name]_integration.png" />
</Frame>

<!--
Screenshot should be placed at: apps/opik-documentation/documentation/fern/img/tracing/[integration_name]_integration.png
Documentation reference path: /img/tracing/[integration_name]_integration.png
-->

## Feedback Scores and Evaluation

Once your [INTEGRATION_NAME] calls are logged with Opik, you can evaluate your LLM application using Opik's evaluation framework:

```python
from opik.evaluation import evaluate
from opik.evaluation.metrics import Hallucination

# Define your evaluation task
def evaluation_task(x):
    return {
        "message": x["message"],
        "output": x["output"],
        "reference": x["reference"]
    }

# Create the Hallucination metric
hallucination_metric = Hallucination()

# Run the evaluation
evaluation_results = evaluate(
    experiment_name="[integration_name]-evaluation",
    dataset=your_dataset,
    task=evaluation_task,
    scoring_metrics=[hallucination_metric],
)
```

## Environment Variables

Make sure to set the following environment variables:

```bash
# [INTEGRATION_NAME] Configuration
export [INTEGRATION_API_KEY_NAME]="your-[integration-name]-api-key"

# Opik Configuration
export OPIK_PROJECT_NAME="your-project-name"
export OPIK_WORKSPACE="your-workspace-name"
```

## Troubleshooting

### Common Issues

1. **Authentication Errors**: Ensure your API key is correct and has the necessary permissions
2. **Model Not Found**: Verify the model name is available on [INTEGRATION_NAME]
3. **Rate Limiting**: [INTEGRATION_NAME] may have rate limits; implement appropriate retry logic
4. **Base URL Issues**: Ensure the base URL is correct for your [INTEGRATION_NAME] deployment

### Getting Help

- Check the [INTEGRATION_NAME] API documentation for detailed error codes
- Review the [INTEGRATION_NAME] status page for service issues
- Contact [INTEGRATION_NAME] support for API-specific problems
- Check Opik documentation for tracing and evaluation features

## Next Steps

Once you have [INTEGRATION_NAME] integrated with Opik, you can:

- [Evaluate your LLM applications](/evaluation/overview) using Opik's evaluation framework
- [Create datasets](/datasets/overview) to test and improve your models
- [Set up feedback collection](/feedback/overview) to gather human evaluations
- [Monitor performance](/tracing/overview) across different models and configurations
