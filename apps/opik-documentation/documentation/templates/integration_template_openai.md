# Integration Template: OpenAI-Based Integration

**Use this template for**: Integrations that use OpenAI-compatible APIs (like BytePlus, OpenRouter, etc.) and can be integrated using Opik's OpenAI integration.

**Requirements**:

- Uses OpenAI-compatible API
- Users install Opik Python SDK
- Uses `track_openai()` wrapper
- Compatible with OpenAI SDK

**Examples**: BytePlus, OpenRouter, Any OpenAI-compatible API

---

## Template Structure

[INTEGRATION_NAME]([INTEGRATION_WEBSITE_URL]) is [INTEGRATION_DESCRIPTION].

This guide explains how to integrate Opik with [INTEGRATION_NAME] using the OpenAI SDK. [INTEGRATION_NAME] provides [SPECIFIC_DESCRIPTION].

## Getting started

First, ensure you have both `opik` and `openai` packages installed:

```bash
pip install opik openai
```

You'll also need a [INTEGRATION_NAME] API key which you can get from [INTEGRATION_WEBSITE_URL].

## Tracking [INTEGRATION_NAME] API calls

```python
from opik.integrations.openai import track_openai
from openai import OpenAI

# Initialize the OpenAI client with [INTEGRATION_NAME] base URL
client = OpenAI(
    base_url="[INTEGRATION_BASE_URL]",
    api_key="YOUR_[INTEGRATION_API_KEY_NAME]"
)
client = track_openai(client)

response = client.chat.completions.create(
    model="[EXAMPLE_MODEL_NAME]",  # You can use any model available on [INTEGRATION_NAME]
    messages=[
        {"role": "user", "content": "Hello, world!"}
    ],
    temperature=0.7,
    max_tokens=100
)

print(response.choices[0].message.content)
```

## Available Models

[INTEGRATION_NAME] provides access to [MODEL_DESCRIPTION].

- [MODEL_CATEGORY_1] ([MODEL_EXAMPLES])
- [MODEL_CATEGORY_2] ([MODEL_EXAMPLES])
- [MODEL_CATEGORY_3] ([MODEL_EXAMPLES])
- And many [OTHER_MODEL_TYPES]

You can find the complete list of available models in the [INTEGRATION_NAME] documentation.

## Supported Methods

[INTEGRATION_NAME] supports the following methods:

### Chat Completions

- `client.chat.completions.create()`: Works with all models
- Provides standard chat completion functionality
- Compatible with the OpenAI SDK interface

### Structured Outputs

- `client.beta.chat.completions.parse()`: Only compatible with OpenAI models
- For non-OpenAI models, see [INTEGRATION_NAME]'s [STRUCTURED_OUTPUTS_DOCUMENTATION]

For detailed information about available methods, parameters, and best practices, refer to the [INTEGRATION_NAME] API documentation.

## Advanced Usage

### Using with @track decorator

You can combine the tracked client with Opik's `@track` decorator for comprehensive tracing:

```python
from opik import track
from opik.integrations.openai import track_openai
from openai import OpenAI

client = OpenAI(
    base_url="[INTEGRATION_BASE_URL]",
    api_key="YOUR_[INTEGRATION_API_KEY_NAME]"
)
client = track_openai(client)

@track
def analyze_data_with_ai(query: str):
    """Analyze data using [INTEGRATION_NAME] AI models."""

    response = client.chat.completions.create(
        model="[EXAMPLE_MODEL_NAME]",
        messages=[
            {"role": "user", "content": query}
        ]
    )

    return response.choices[0].message.content

# Call the tracked function
result = analyze_data_with_ai("Analyze this business data...")
```

### Streaming Responses

[INTEGRATION_NAME] supports streaming responses:

```python
response = client.chat.completions.create(
    model="[EXAMPLE_MODEL_NAME]",
    messages=[
        {"role": "user", "content": "Tell me a story about AI"}
    ],
    stream=True
)

for chunk in response:
    if chunk.choices[0].delta.content is not None:
        print(chunk.choices[0].delta.content, end="")
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

For more information about using Opik with OpenAI-compatible APIs, see the [OpenAI integration guide](/integrations/openai).
