---
description: Start here to integrate OpenRouter with Opik using the Python SDK.
title: Observability for OpenRouter (Python) with Opik
---

This package provides a dedicated integration for the OpenRouter Python SDK.

## Features

- Tracks OpenRouter `chat.send` calls via `track_openrouter`
- Supports non-streaming and async chat calls where available
- Sets span provider to `openrouter`

## Installation

```bash
pip install opik
```

Use your OpenRouter client package alongside `opik` in your app.

## Usage

```python
from opik.integrations.openrouter import track_openrouter

# Replace with your OpenRouter SDK client instance.
openrouter_client = get_openrouter_client(api_key="YOUR_OPENROUTER_API_KEY")
tracked_client = track_openrouter(openrouter_client)

response = tracked_client.chat.send(
    model="openai/gpt-4",
    messages=[{"role": "user", "content": "Hello, world!"}],
)
```

If tracing is active, calls are logged to your active Opik project automatically.

Remember to flush your Opik client according to your environment lifecycle.
