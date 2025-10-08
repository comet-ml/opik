# Extending the Chunk Aggregator

This guide demonstrates how to add support for a new Bedrock model format.

Each format aggregator should return the response in the **model's native structure**, with usage standardized to Bedrock format (camelCase: `inputTokens`, `outputTokens`, `totalTokens`).

## Example: Adding Titan Support

Let's walk through adding support for Amazon Titan models.

### Step 1: Create the Aggregator Module

Create `titan.py`:

```python
"""Amazon Titan chunk aggregator."""

import json
from typing import Any, Dict, List


class TitanAggregator:
    """
    Aggregator for Amazon Titan streaming format.

    Returns Titan's native output format with Bedrock usage.

    Titan chunk structure (example):
    - outputText: Contains generated text
    - completionReason: Contains stop reason
    """

    def aggregate(self, items: List[Dict[str, Any]]) -> Dict[str, Any]:
        """Aggregate Titan format chunks into native Titan output with Bedrock usage."""
        output_text = ""
        stop_reason = None
        input_tokens = 0
        output_tokens = 0

        for item in items:
            if "chunk" not in item:
                continue

            try:
                chunk_data = json.loads(item["chunk"]["bytes"])

                # Extract text
                if "outputText" in chunk_data:
                    output_text += chunk_data["outputText"]

                # Extract stop reason
                if "completionReason" in chunk_data:
                    stop_reason = chunk_data["completionReason"]

                # Extract token counts
                if "inputTokenCount" in chunk_data:
                    input_tokens = chunk_data["inputTokenCount"]
                if "outputTokenCount" in chunk_data:
                    output_tokens = chunk_data["outputTokenCount"]

                # Also check for bedrock metrics (authoritative)
                metrics = chunk_data.get("amazon-bedrock-invocationMetrics", {})
                if metrics:
                    input_tokens = metrics.get("inputTokenCount", input_tokens)
                    output_tokens = metrics.get("outputTokenCount", output_tokens)

            except Exception:
                continue

        # Return Titan's native output format with Bedrock usage
        return {
            "outputText": output_text,
            "completionReason": stop_reason,
            "usage": {
                "inputTokens": input_tokens,
                "outputTokens": output_tokens,
                "totalTokens": input_tokens + output_tokens,
            }
        }
```

### Step 2: Register the Format

Update `format_detector.py`:

```python
from . import titan

def _is_titan_format(chunk_data: Dict[str, Any]) -> bool:
    """Check if chunk is Titan format."""
    return "outputText" in chunk_data or "completionReason" in chunk_data

# Add to the registration section (after existing registrations)
# Add to _DETECTORS registry
_DETECTORS["titan"] = _is_titan_format

# Add to _AGGREGATORS registry
_AGGREGATORS["titan"] = titan.TitanAggregator()
```

### Step 3: Test Your New Format

```python
import boto3
import json
from opik.integrations.bedrock import track_bedrock

client = boto3.client("bedrock-runtime", region_name="us-east-1")
client = track_bedrock(client)

response = client.invoke_model_with_response_stream(
    modelId="amazon.titan-text-express-v1",
    body=json.dumps({
        "inputText": "What is AI?",
        "textGenerationConfig": {
            "maxTokenCount": 100,
            "temperature": 0.7
        }
    })
)

# Format is automatically detected and aggregated!
for event in response['body']:
    if 'chunk' in event:
        chunk_data = json.loads(event['chunk']['bytes'])
        print(chunk_data.get('outputText', ''), end='')
```

## Key Points

1. **No Core Changes**: You never modify `__init__.py` or existing aggregators
2. **Self-Contained**: Each format is completely isolated in its own module
3. **Registry Pattern**: Simply register your detector and aggregator
4. **Type Safety**: Follow the `ChunkAggregator` protocol
5. **Error Handling**: Use try/except to skip malformed chunks
6. **Standardized Output**: Return Dict with provider-native structure and Bedrock usage

## Testing Your Extension

Create a unit test for your new aggregator:

```python
import json
from chunks_aggregator import titan

def test_titan_aggregator():
    chunks = [
        {"chunk": {"bytes": json.dumps({"outputText": "Hello "}).encode()}},
        {"chunk": {"bytes": json.dumps({"outputText": "World"}).encode()}},
        {"chunk": {"bytes": json.dumps({
            "completionReason": "FINISH",
            "inputTokenCount": 5,
            "outputTokenCount": 10
        }).encode()}},
    ]

    aggregator = titan.TitanAggregator()
    result = aggregator.aggregate(chunks)

    assert result["text"] == "Hello World"
    assert result["usage"]["input_tokens"] == 5
    assert result["usage"]["output_tokens"] == 10
    assert result["stop_reason"] == "FINISH"
```

## Architecture Benefits

### Before (Single File)
```python
# Mixed logic in one function
if claude_condition:
    # Claude logic
elif nova_condition:
    # Nova logic
elif titan_condition:  # Adding this requires modifying existing code
    # Titan logic
```

### After (Modular)
```python
# Each format is isolated
claude.py       # Claude logic only
nova.py         # Nova logic only
titan.py        # Titan logic only (new file, no changes to existing code)
```

### Extensibility Comparison

| Aspect | Single File | Modular Package |
|--------|------------|-----------------|
| Add new format | Modify existing function | Create new file + register |
| Test format | Test entire function | Test single aggregator |
| Understand format | Read mixed logic | Read isolated module |
| Risk of breaking | High (one change affects all) | Low (isolated changes) |
| Code review | Large, complex diffs | Small, focused diffs |

## Real-World Extension Example

Here's how you would add support for a completely custom format:

```python
# custom.py
class CustomAggregator:
    """Your custom format aggregator."""

    def aggregate(self, items):
        # Your custom aggregation logic
        text = self._extract_text(items)
        usage = self._extract_usage(items)
        stop_reason = self._extract_stop_reason(items)
        return {
            "text": text,
            "usage": {
                "inputTokens": usage.get("input_tokens", 0),
                "outputTokens": usage.get("output_tokens", 0),
                "totalTokens": usage.get("input_tokens", 0) + usage.get("output_tokens", 0),
            },
            "stop_reason": stop_reason
        }

    def _extract_text(self, items):
        """Extract text using your custom logic."""
        ...

    def _extract_usage(self, items):
        """Extract usage using your custom logic."""
        ...

    def _extract_stop_reason(self, items):
        """Extract stop reason using your custom logic."""
        ...
```

Then register it:

```python
# format_detector.py
from . import custom

def _is_custom_format(chunk_data):
    return "your_marker" in chunk_data

# Add to _DETECTORS registry
_DETECTORS["custom"] = _is_custom_format

# Add to _AGGREGATORS registry
_AGGREGATORS["custom"] = custom.CustomAggregator()
```

That's it! Your custom format is now fully integrated.
