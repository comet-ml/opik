---
title: Observability for [INTEGRATION_NAME] with Opik
description: Start here to integrate Opik into your [INTEGRATION_NAME]-based genai application for end-to-end LLM observability, unit testing, and optimization.
---

[INTEGRATION_NAME]([INTEGRATION_WEBSITE_URL]) is [INTEGRATION_DESCRIPTION].

This guide explains how to integrate Opik with [INTEGRATION_NAME] using the [INTEGRATION_NAME] integration provided by Opik. By using the [INTEGRATION_NAME] integration provided by Opik, you can easily track and evaluate your [INTEGRATION_NAME] API calls within your Opik projects as Opik will automatically log the input prompt, model used, token usage, and response generated.

## Account Setup

[Comet](https://www.comet.com/site?from=llm&utm_source=opik&utm_medium=colab&utm_content=[integration_name]&utm_campaign=opik) provides a hosted version of the Opik platform, [simply create an account](https://www.comet.com/signup?from=llm&utm_source=opik&utm_medium=colab&utm_content=[integration_name]&utm_campaign=opik) and grab your API Key.

> You can also run the Opik platform locally, see the [installation guide](https://www.comet.com/docs/opik/self-host/overview/?from=llm&utm_source=opik&utm_medium=colab&utm_content=[integration_name]&utm_campaign=opik) for more information.

## Getting Started

### Installation

Install the required packages:

```bash
pip install opik [integration_package]
```

### Configuring Opik

Configure the Opik Python SDK for your deployment type. See the [Python SDK Configuration guide](/tracing/sdk_configuration) for detailed instructions on:

- **CLI configuration**: `opik configure`
- **Code configuration**: `opik.configure()`
- **Self-hosted vs Cloud vs Enterprise** setup
- **Configuration files** and environment variables

### Configuring [INTEGRATION_NAME]

In order to configure [INTEGRATION_NAME], you will need to have your [INTEGRATION_NAME] API Key. You can [find or create your [INTEGRATION_NAME] API Key in this page]([INTEGRATION_API_KEY_URL]).

You can set it as an environment variable:

```bash
export [INTEGRATION_API_KEY_NAME]="YOUR_API_KEY"
```

Or set it programmatically:

```python
import os
import getpass

if "[INTEGRATION_API_KEY_NAME]" not in os.environ:
    os.environ["[INTEGRATION_API_KEY_NAME]"] = getpass.getpass("Enter your [INTEGRATION_NAME] API key: ")

# Set project name for organization
os.environ["OPIK_PROJECT_NAME"] = "[integration_name]-integration-demo"
```

## Usage

### Basic Usage

Set up [INTEGRATION_NAME] with Opik tracking:

```python
from opik.integrations.[integration_module] import track_[integration_name]
from [package] import [ClientClass]

# Initialize the [INTEGRATION_NAME] client
client = [ClientClass]()
tracked_client = track_[integration_name](client)

# Set project name for organization
os.environ["OPIK_PROJECT_NAME"] = "[integration_name]-integration-demo"

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

## Required Placeholders

Replace these placeholders in templates:

**Code Integrations:**

- `[INTEGRATION_NAME]` → Actual integration name (e.g., "OpenAI")
- `[integration_name]` → Lowercase version (e.g., "openai")
- `[integration_module]` → Python module name (e.g., "openai")
- `[integration_package]` → Package to install (e.g., "openai")
- `[ClientClass]` → Main client class (e.g., "OpenAI")
- `[INTEGRATION_API_KEY_NAME]` → Environment variable name (e.g., "OPENAI_API_KEY")
- `[INTEGRATION_API_KEY_URL]` → URL where users can create/manage API keys
- `[INTEGRATION_WEBSITE_URL]` → Main website URL for the integration
- `[INTEGRATION_DESCRIPTION]` → Brief description of what the integration does
