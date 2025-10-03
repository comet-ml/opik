"""
Bedrock invoke_model_with_response_stream chunk aggregator.

DISCLAIMER: This package was generated with AI assistance.

This package provides a modular, extensible architecture for aggregating
streaming chunks from different Bedrock model formats (Claude, Nova, etc.).

## Public API

The main public function is `aggregate_chunks_to_dataclass()` which returns a
structured `BedrockAggregatedResponse` dataclass for type safety.

```python
from opik.integrations.bedrock.invoke_model.chunks_aggregator import aggregate_chunks_to_dataclass

# Create typed aggregated response
response = aggregate_chunks_to_dataclass(chunks)

# Access native format and usage
native_output = response.native_response  # Provider-specific format
usage = response.usage  # Bedrock format: {inputTokens, outputTokens, totalTokens}

# Use in span logging
output = response.to_output_format()  # {"body": native_response}
metadata = response.to_metadata_format()  # {"created_from": "bedrock", ...}
```

Each format aggregator returns the response in its native structure,
with usage standardized to Bedrock format (camelCase: inputTokens, outputTokens, totalTokens).

## Architecture

- `base`: Base types and protocols
- `claude`: Claude/Anthropic format aggregator
- `llama`: Meta Llama format aggregator
- `mistral`: Mistral/Pixtral format aggregator
- `nova`: Amazon Nova format aggregator
- `format_detector`: Format detection and aggregator registry
- `response_types`: Dataclass definitions for structured responses

## Adding New Formats

To add support for a new model format:

1. Create a new aggregator module (e.g., `titan.py`):
   ```python
   class TitanAggregator:
       def aggregate(self, items) -> Dict[str, Any]:
           # Return Titan's native structure with Bedrock usage
           return {
               "outputText": "...",
               "usage": {"inputTokens": 10, "outputTokens": 20, "totalTokens": 30}
           }
   ```

2. Add detection and registration in `format_detector.py`:
   ```python
   from . import titan

   def _is_titan_format(chunk_data):
       return "titan_specific_field" in chunk_data

   # Add to _DETECTORS registry
   _DETECTORS["titan"] = _is_titan_format

   # Add to _AGGREGATORS registry
   _AGGREGATORS["titan"] = titan.TitanAggregator()
   ```

The new format will be automatically detected and used.
"""

from .api import aggregate_chunks_to_dataclass

__all__ = [
    "aggregate_chunks_to_dataclass",
]
