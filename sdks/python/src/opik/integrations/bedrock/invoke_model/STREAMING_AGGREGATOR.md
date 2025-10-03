# Bedrock invoke_model_with_response_stream Aggregator

## Overview

The `chunks_aggregator` package provides a modular, extensible architecture for aggregating streaming chunks from AWS Bedrock's `invoke_model_with_response_stream` API. Each model format (Claude, Nova, etc.) is handled by a dedicated aggregator module, making it easy to add support for new formats without modifying existing code.

## Supported Models

The aggregator handles two different streaming formats:

### 1. Claude Models (Anthropic)
- Uses snake_case field names: `content_block_delta`, `message_start`, `message_delta`, `message_stop`
- Chunk structure:
  - `message_start`: Initial metadata and usage
  - `content_block_delta`: Text chunks in `delta.text`
  - `message_delta`: Stop reason and updated usage
  - `message_stop`: Final metrics in `amazon-bedrock-invocationMetrics`

### 2. Nova Models (Amazon)
- Uses camelCase field names: `contentBlockDelta`, `messageStart`, `messageStop`
- Chunk structure:
  - `messageStart`: Role information
  - `contentBlockDelta`: Text chunks in `delta.text`
  - `messageStop`: Stop reason
  - Final chunk with `metadata.usage` and `amazon-bedrock-invocationMetrics`

## Architecture

The package uses a modular architecture with clear separation of concerns:

```
chunks_aggregator/
├── __init__.py              # Public API and orchestration
├── _base.py                 # Base types and protocols
├── _claude.py               # Claude/Anthropic aggregator
├── _nova.py                 # Amazon Nova aggregator
└── _format_detector.py      # Format detection and registry
```

### Components

1. **Base Types** (`_base.py`): Defines `AggregatedData` and `ChunkAggregator` protocol
2. **Format Aggregators**: Each format has a dedicated module (`_claude.py`, `_nova.py`)
3. **Format Detection** (`_format_detector.py`): Registry-based format detection
4. **Public API** (`__init__.py`): Main aggregation function and extension points

### Benefits

- ✅ **No Mixed Logic**: Each format is completely isolated
- ✅ **Easy to Extend**: Add new formats without touching existing code
- ✅ **Type Safe**: Protocol-based design with full type hints
- ✅ **Testable**: Each aggregator can be tested independently
- ✅ **Registry Pattern**: Dynamic format registration
- ✅ **Self-Documenting**: Each module clearly describes its format

## Output Format

```python
{
    "body": {
        "content": [{"text": "aggregated text", "type": "text"}],
        "role": "assistant",
        "type": "message",
        "stop_reason": "end_turn",  # if available
        "usage": {
            "input_tokens": 22,
            "output_tokens": 66
        }
    }
}
```

## Usage

The aggregator is automatically applied when tracking Bedrock streaming calls:

```python
from opik.integrations.bedrock import track_bedrock
import boto3

client = boto3.client("bedrock-runtime")
client = track_bedrock(client)

# Streaming is automatically tracked and aggregated
response = client.invoke_model_with_response_stream(
    modelId="us.anthropic.claude-3-5-sonnet-20241022-v2:0",
    body=json.dumps({...})
)
```

## Extending with New Formats

To add support for a new format:

### 1. Create Aggregator Module

```python
# _titan.py
from ._base import AggregatedData

class TitanAggregator:
    def aggregate(self, items):
        # Your aggregation logic
        return AggregatedData(text=text, usage=usage, stop_reason=stop_reason)
```

### 2. Register Format

```python
# _format_detector.py
from ._titan import TitanAggregator

register_detector("titan", lambda chunk: "titan_field" in chunk)
register_aggregator("titan", TitanAggregator())
```

### 3. Done!

Your format is now automatically detected and used. See `chunks_aggregator/EXTENDING.md` for detailed instructions.

## Testing

All supported models have been tested:
- ✅ Claude 3.5 Sonnet (multiple versions)
- ✅ Claude 3.7 Sonnet
- ✅ Claude Sonnet 4
- ✅ Amazon Nova Pro

All models correctly aggregate text, usage, and metadata from streaming responses.

The modular architecture has been verified to support dynamic format registration without modifying core code.
