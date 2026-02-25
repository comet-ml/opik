# Code Quality Patterns

## Access Control

Methods used only inside their class should be private.

```python
# ✅ Good
class DataProcessor:
    def process(self, data):        # Public interface
        cleaned = self._clean(data)
        return self._format(cleaned)

    def _clean(self, data):         # Private - only used internally
        pass

    def _format(self, data):        # Private - only used internally
        pass
```

## Module Organization

One module, one responsibility. Avoid monolithic utils.

```python
# ✅ Good: Focused modules
# httpx_client.py - Only HTTP client
# config.py - Only configuration

# ❌ Bad: Kitchen sink module
# utils.py
class HttpClient: ...
class ConfigManager: ...
def parse_json(): ...
def format_date(): ...
```

## Import Organization

```python
# Standard library
import logging
from typing import Any, Optional

# Third-party
import httpx

# Local - import modules, not names
from opik import config, exceptions
from opik.message_processing import messages

# TYPE_CHECKING for circular imports
from typing import TYPE_CHECKING
if TYPE_CHECKING:
    from langchain_core.messages import BaseMessage
```

## Factory Pattern for Extension

```python
# ✅ Good: Easy to add new providers
_PROVIDER_BUILDERS = {
    LLMProvider.OPENAI: [OpikUsage.from_openai_dict],
    LLMProvider.ANTHROPIC: [OpikUsage.from_anthropic_dict],
}

def build_usage(provider, usage):
    for builder in _PROVIDER_BUILDERS[provider]:
        try:
            return builder(usage)
        except Exception:
            continue
    raise ValueError(f"Failed for {provider}")
```

## Dependency Injection

```python
# ✅ Good: Dependencies injected
class Streamer:
    def __init__(
        self,
        queue: MessageQueue,           # Injected
        batch_manager: BatchManager,   # Injected
    ):
        self._queue = queue
        self._batch_manager = batch_manager

# ❌ Bad: Dependencies created internally
class Streamer:
    def __init__(self):
        self._queue = MessageQueue()         # Hard to test
        self._batch_manager = BatchManager() # Hard to test
```

## Avoid Redundant Parameters

```python
# ❌ Bad: Passing data already stored
def validate_span(self, data: Dict) -> bool:
    return data.get("span_id") is not None

# ✅ Good: Use internal state
def validate_span(self) -> bool:
    return self._span_data.get("span_id") is not None
```
