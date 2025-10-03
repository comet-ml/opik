# Bedrock Chunk Aggregator

A modular, extensible architecture for aggregating streaming chunks from AWS Bedrock's `invoke_model_with_response_stream` API.

Each format aggregator returns the response in its **native structure**, with usage standardized to Bedrock format (camelCase: `inputTokens`, `outputTokens`, `totalTokens`).

## Architecture

```
chunks_aggregator/
├── __init__.py              # Public API and main aggregation logic
├── base.py                  # Base types and protocols
├── claude.py                # Claude/Anthropic aggregator
├── llama.py                 # Meta Llama aggregator
├── mistral.py               # Mistral/Pixtral aggregator
├── nova.py                  # Amazon Nova aggregator
└── format_detector.py       # Format detection and registry
```

## Supported Formats

- **Claude** (Anthropic): Claude 3.5 Sonnet, Claude 3.7 Sonnet, Claude Sonnet 4
  - Returns: `{role, content, stop_reason, usage}`
- **Nova** (Amazon): Nova Pro
  - Returns: `{output: {message: {role, content}}, stopReason, usage}`
- **Llama** (Meta): Llama 3.1 8B, Llama 3.3 70B
  - Returns: `{generation, prompt_token_count, generation_token_count, stop_reason, usage}`
- **Mistral** (Mistral AI): Pixtral Large 2502
  - Returns: `{id, object, model, choices, usage}` (OpenAI-compatible)

## Adding a New Format

### Step 1: Create Aggregator Module

Create a new file `_yourformat.py`:

```python
"""Your format chunk aggregator."""

import json
from typing import Any, Dict, List

from . import base


class YourFormatAggregator:
    """Aggregator for Your Format streaming format."""

    def aggregate(self, items: List[Dict[str, Any]]) -> Dict[str, Any]:
        """Aggregate Your Format chunks."""
        text = ""
        usage: Dict[str, Any] = {}
        stop_reason = None

        for item in items:
            if "chunk" not in item:
                continue

            try:
                chunk_data = json.loads(item["chunk"]["bytes"])

                # Extract text from your format's structure
                if "your_text_field" in chunk_data:
                    text += chunk_data["your_text_field"]

                # Extract usage from your format's structure
                if "your_usage_field" in chunk_data:
                    usage = chunk_data["your_usage_field"]

                # Extract stop reason
                if "your_stop_field" in chunk_data:
                    stop_reason = chunk_data["your_stop_field"]

            except Exception:
                continue

        return {
            "text": text,
            "usage": {
                "inputTokens": usage.get("input_tokens", 0),
                "outputTokens": usage.get("output_tokens", 0),
                "totalTokens": usage.get("input_tokens", 0) + usage.get("output_tokens", 0),
            },
            "stop_reason": stop_reason
        }
```

### Step 2: Register Format

Update `format_detector.py`:

```python
from . import yourformat

def _is_yourformat_format(chunk_data: Dict[str, Any]) -> bool:
    """Check if chunk is YourFormat format."""
    return "your_unique_field" in chunk_data

# Register detector and aggregator
# Add to _DETECTORS registry
_DETECTORS["yourformat"] = _is_yourformat_format

# Add to _AGGREGATORS registry
_AGGREGATORS["yourformat"] = yourformat.YourFormatAggregator()
```

### Step 3: Done!

Your new format is now automatically supported:

```python
response = client.invoke_model_with_response_stream(
    modelId="your-model-id",
    body=json.dumps({...})
)

# Automatically detects and aggregates your format
result = aggregate_chunks_to_dataclass(list(response['body']))
```

## Benefits

- **Separation of Concerns**: Each format has its own isolated module
- **Easy Testing**: Test each format independently
- **No Mixed Logic**: Clear boundaries between formats
- **Registry Pattern**: Add new formats without modifying existing code
- **Type Safety**: Protocol-based design with type hints
- **Extensible**: Add formats without touching core logic

## Testing

Each format can be tested independently:

```python
from chunks_aggregator import claude

aggregator = claude.ClaudeAggregator()
result = aggregator.aggregate(claude_chunks)
assert result["text"] == expected_text
```

## Format Detection

Format detection is automatic and extensible:

1. Iterate through chunks until a recognizable format is found
2. Try each registered detector in order
3. Return the first matching format
4. Default to Claude if no match found

Add custom detectors to support new formats without modifying core code.
