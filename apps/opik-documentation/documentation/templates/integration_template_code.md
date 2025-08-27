---
description: Cookbook that showcases Opik's integration with [INTEGRATION_NAME]
---

# Using Opik with [INTEGRATION_NAME]

Opik integrates with [INTEGRATION_NAME] to provide a simple way to log traces for all [LLM/Agent/Framework] calls. [Add brief description of what gets tracked]

## Creating an account on Comet.com

[Comet](https://www.comet.com/site?from=llm&utm_source=opik&utm_medium=colab&utm_content=[integration_name]&utm_campaign=opik) provides a hosted version of the Opik platform, [simply create an account](https://www.comet.com/signup?from=llm&utm_source=opik&utm_medium=colab&utm_content=[integration_name]&utm_campaign=opik) and grab you API Key.

> You can also run the Opik platform locally, see the [installation guide](https://www.comet.com/docs/opik/self-host/overview/?from=llm&utm_source=opik&utm_medium=colab&utm_content=[integration_name]&utm_campaign=opik) for more information.

## Installation

Install the required packages:

```python
%pip install --upgrade opik [integration_package_name]
```

## Configuration

Configure Opik to point to your desired backend:

```python
import opik

opik.configure(use_local=False)  # For Comet.com cloud
# opik.configure(use_local=True)  # For local self-hosted
```

## Environment Setup

Set up your API keys:

```python
import os
import getpass

# [INTEGRATION_NAME] API key
if "[INTEGRATION_API_KEY_NAME]" not in os.environ:
    os.environ["[INTEGRATION_API_KEY_NAME]"] = getpass.getpass("Enter your [INTEGRATION_NAME] API key: ")

# Set project name (optional)
os.environ["OPIK_PROJECT_NAME"] = "[integration_name]-integration-demo"
```

## Basic Usage

Import the integration and wrap your client:

```python
from opik.integrations.[integration_module] import track_[integration_name]
from [integration_package] import [ClientClass]

# Create your original client
client = [ClientClass]([constructor_params])

# Wrap with Opik tracking
tracked_client = track_[integration_name](client)
# OR for global tracking:
# track_[integration_name](project_name="my-project")
```

### Simple Example

```python
# Example usage
[example_code_with_tracked_client]

# The calls are automatically logged to Opik
```

## Advanced Usage

### Using with `@track` decorator

If you have multiple steps in your pipeline, combine with Opik's `@track` decorator:

```python
from opik import track

@track
def my_pipeline_step(input_data):
    # Your [INTEGRATION_NAME] calls here
    result = tracked_client.[some_method](input_data)
    return result

@track  
def full_pipeline():
    step1_result = my_pipeline_step("input")
    # Additional processing...
    return final_result

full_pipeline()
```

### [Integration-Specific Advanced Features]

[Add any integration-specific advanced usage patterns, like:
- Streaming support
- Async support  
- Multi-agent workflows
- Custom metadata
- Error handling
- etc.]

## Viewing Results

The traces will be automatically logged to Opik and can be viewed in the UI:

![Integration Screenshot](https://path/to/screenshot.png)

## Troubleshooting

### Common Issues

1. **Missing API Key**: Ensure your [INTEGRATION_NAME] API key is set correctly
2. **Import Errors**: Make sure both `opik` and `[integration_package]` are installed
3. **No Traces Appearing**: Verify your Opik configuration with `opik configure`

### Getting Help

If you encounter issues:
- Check the [Opik documentation](https://www.comet.com/docs/opik/)
- Join our [Slack community](https://chat.comet.com)
- Report issues on [GitHub](https://github.com/comet-ml/opik/issues) 