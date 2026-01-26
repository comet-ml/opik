# OpenAI Text-to-Speech (TTS) Tracking

This document describes the OpenAI TTS tracking feature added to Opik, which enables automatic tracking of Text-to-Speech API calls with character-based usage and cost calculation.

## Overview

OpenAI's TTS models (`tts-1` and `tts-1-hd`) convert text to natural-sounding speech. Unlike chat completion models that are priced per token, TTS models are priced per character. This integration automatically tracks:

- **Character count** from input text
- **Model used** (tts-1 or tts-1-hd)
- **Voice selection** (alloy, echo, fable, onyx, nova, shimmer)
- **Audio parameters** (speed, response format)
- **Cost estimation** based on character count

## Pricing

As of 2025, OpenAI TTS pricing is:

| Model | Price per 1,000 characters |
|-------|---------------------------|
| tts-1 | $0.015 |
| tts-1-hd | $0.030 |

## Features

✅ **Automatic Tracking**: No code changes needed beyond wrapping the client  
✅ **Character-Based Usage**: Accurate character count for cost calculation  
✅ **Model Detection**: Automatically detects tts-1 vs tts-1-hd  
✅ **Voice Tracking**: Records which voice was used  
✅ **Parameter Logging**: Tracks speed, format, and other parameters  
✅ **Async Support**: Works with both sync and async OpenAI clients  
✅ **Cost Estimation**: Easy cost calculation from character count  

## Installation

No additional installation required. TTS tracking is included in the Opik OpenAI integration.

```bash
pip install opik openai
```

## Quick Start

```python
import openai
from opik.integrations.openai import track_openai

# Create and track OpenAI client
client = openai.OpenAI()
client = track_openai(client, project_name="my-tts-project")

# Use TTS as normal - tracking happens automatically
response = client.audio.speech.create(
    model="tts-1",
    voice="alloy",
    input="Hello, this is a test of text to speech tracking!"
)

# Save the audio
with open("output.mp3", "wb") as f:
    f.write(response.content)
```

That's it! The TTS call is now tracked in Opik with character count and usage data.

## Usage Examples

### Basic TTS Tracking

```python
import openai
from opik.integrations.openai import track_openai

client = openai.OpenAI()
client = track_openai(client)

# Generate speech
response = client.audio.speech.create(
    model="tts-1",
    voice="alloy",
    input="Your text here"
)

# Character count is automatically tracked
# View in Opik dashboard: https://www.comet.com/opik
```

### High-Definition TTS

```python
# Use tts-1-hd for higher quality (2x cost)
response = client.audio.speech.create(
    model="tts-1-hd",
    voice="nova",
    input="High quality audio output",
    speed=1.25  # Adjust playback speed
)
```

### Different Voices

```python
voices = ["alloy", "echo", "fable", "onyx", "nova", "shimmer"]

for voice in voices:
    response = client.audio.speech.create(
        model="tts-1",
        voice=voice,
        input="Testing different voices"
    )
    # Each call is tracked separately
```

### Cost Tracking

```python
import opik

# Track multiple TTS calls
texts = [
    "First segment",
    "Second segment with more content",
    "Third segment"
]

total_chars = 0
for text in texts:
    response = client.audio.speech.create(
        model="tts-1",
        voice="alloy",
        input=text
    )
    total_chars += len(text)

# Calculate total cost
cost = (total_chars / 1000) * 0.015  # $0.015 per 1K chars for tts-1
print(f"Total cost: ${cost:.6f}")

# Flush to Opik
opik.flush_tracker()
```

### Async TTS

```python
import asyncio
import openai
from opik.integrations.openai import track_openai

async def generate_speech():
    client = openai.AsyncOpenAI()
    client = track_openai(client)
    
    response = await client.audio.speech.create(
        model="tts-1",
        voice="alloy",
        input="Async TTS example"
    )
    
    return response

# Run async
audio = asyncio.run(generate_speech())
```

## What Gets Tracked

When you make a TTS API call, Opik tracks:

### Input Data
- `input`: The text being converted to speech
- `voice`: Selected voice (alloy, echo, fable, onyx, nova, shimmer)
- `response_format`: Audio format (mp3, opus, aac, flac, wav, pcm)
- `speed`: Playback speed (0.25 to 4.0)

### Metadata
- `model`: TTS model used (tts-1 or tts-1-hd)
- `type`: "openai_tts"
- `created_from`: "openai"
- `voice`: Voice selection
- `response_format`: Audio format
- `speed`: Playback speed

### Usage Data
- `characters`: Total character count from input text
- `original_usage.characters`: Character count (for backend compatibility)

### Output Data
- `audio_generated`: Boolean indicating successful generation
- `character_count`: Number of characters processed

## Viewing Tracked Data

### Opik Dashboard

1. Go to https://www.comet.com/opik
2. Select your project
3. View TTS traces with:
   - Character count
   - Model and voice used
   - Input text
   - Cost estimation

### Programmatic Access

```python
import opik

# Get client
client = opik.Opik()

# Retrieve traces
traces = client.get_traces(project_name="my-tts-project")

for trace in traces:
    for span in trace.spans:
        if span.type == "llm" and "tts" in span.tags:
            chars = span.usage.get("original_usage.characters", 0)
            model = span.model
            
            # Calculate cost
            cost_per_1k = 0.015 if model == "tts-1" else 0.030
            cost = (chars / 1000) * cost_per_1k
            
            print(f"TTS Call: {chars} chars, ${cost:.6f}")
```

## Cost Calculation

### Formula

```python
# For tts-1
cost = (character_count / 1000) * 0.015

# For tts-1-hd
cost = (character_count / 1000) * 0.030
```

### Examples

| Text | Characters | Model | Cost |
|------|-----------|-------|------|
| "Hello world" | 11 | tts-1 | $0.000165 |
| "Hello world" | 11 | tts-1-hd | $0.000330 |
| 1,000 char text | 1,000 | tts-1 | $0.015 |
| 1,000 char text | 1,000 | tts-1-hd | $0.030 |
| 10,000 char text | 10,000 | tts-1 | $0.150 |
| 10,000 char text | 10,000 | tts-1-hd | $0.300 |

## Supported Parameters

All OpenAI TTS parameters are supported and tracked:

- `model`: "tts-1" or "tts-1-hd" (required)
- `voice`: "alloy", "echo", "fable", "onyx", "nova", "shimmer" (required)
- `input`: Text to convert to speech (required)
- `response_format`: "mp3", "opus", "aac", "flac", "wav", "pcm" (optional, default: mp3)
- `speed`: 0.25 to 4.0 (optional, default: 1.0)

## Technical Details

### Character Count Calculation

The character count is calculated using Python's `len()` function on the input text string. This includes:

- All letters and numbers
- Spaces and punctuation
- Unicode characters (counted as single characters)
- Emojis (counted as single characters)

### Usage Data Structure

```python
{
    "characters": 42,  # Total character count
}
```

This is converted to:

```python
{
    "original_usage.characters": 42
}
```

For backend compatibility.

### Span Structure

```python
{
    "name": "tts_create",
    "type": "llm",
    "model": "tts-1",
    "provider": "openai",
    "tags": ["openai", "tts"],
    "input": {
        "input": "Your text here",
        "voice": "alloy",
        "response_format": "mp3",
        "speed": 1.0
    },
    "output": {
        "audio_generated": True,
        "character_count": 15
    },
    "metadata": {
        "created_from": "openai",
        "type": "openai_tts",
        "model": "tts-1",
        "voice": "alloy",
        "response_format": "mp3",
        "speed": 1.0
    },
    "usage": {
        "original_usage.characters": 15
    }
}
```

## Limitations

1. **Audio Content Not Stored**: The actual audio bytes are not stored in Opik (would be too large). Only metadata and character count are tracked.

2. **No Token Tracking**: TTS doesn't use tokens, so `completion_tokens`, `prompt_tokens`, and `total_tokens` are not set.

3. **Character Count Only**: Usage is based solely on input character count, not audio duration or file size.

## Troubleshooting

### TTS calls not being tracked

**Solution**: Make sure you're wrapping the client with `track_openai()`:

```python
client = openai.OpenAI()
client = track_openai(client)  # Don't forget this!
```

### Character count is 0

**Solution**: Ensure the `input` parameter contains text:

```python
# ❌ Wrong
client.audio.speech.create(model="tts-1", voice="alloy", input="")

# ✅ Correct
client.audio.speech.create(model="tts-1", voice="alloy", input="Hello world")
```

### Cost calculation seems wrong

**Solution**: Verify you're using the correct pricing:
- tts-1: $0.015 per 1,000 characters
- tts-1-hd: $0.030 per 1,000 characters

```python
# Calculate cost
chars = 1500
model = "tts-1-hd"
cost_per_1k = 0.015 if model == "tts-1" else 0.030
cost = (chars / 1000) * cost_per_1k  # $0.045 for 1,500 chars with tts-1-hd
```

## Related Issues

- Closes #2202 - Support OpenAI TTS models tracking

## Contributing

Found a bug or have a feature request? Please open an issue on GitHub:
https://github.com/comet-ml/opik/issues

## License

This feature is part of Opik and follows the same license.
