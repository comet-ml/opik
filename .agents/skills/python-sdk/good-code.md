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

## Prefer Module-Level Functions Over Static Methods

A `@staticmethod` touches no instance state, so the class adds nothing but a
longer call path. Make it a module-level function — private (`_name`) when it is
an implementation detail. Reserve `@staticmethod` for the rare case where the
function must be reachable through the class as part of its public API, or where
a subclass is expected to override it.

```python
# ❌ Bad: nothing here needs the class
class Experiment:
    def upload(self, items):
        self._raise_on_oversized(items)

    @staticmethod
    def _raise_on_oversized(items): ...

# ✅ Good: plain function, testable on its own
def _raise_on_oversized(items): ...

class Experiment:
    def upload(self, items):
        _raise_on_oversized(items)
```

## Use Strict Types

Annotate with the narrowest type that is true. `Any` disables type checking
exactly where a mistake is most likely — reach for the concrete type, a
`TypedDict`, or a `Protocol` instead.

```python
# ❌ Bad: Any, then subscripted as if the shape were known
def _to_rest_score(score: Any) -> RestScore:
    return RestScore(name=score["name"], value=score["value"])

# ✅ Good: the shape is declared, so mypy checks the access
def _to_rest_score(score: FeedbackScoreDict) -> RestScore:
    return RestScore(name=score["name"], value=score["value"])
```

`Any` is legitimate for genuinely unvalidated input — a validator whose whole job
is to `isinstance`-check caller data cannot promise the type it is checking for:

```python
# ✅ Good: Any is honest here; the function exists to reject wrong shapes
def _validate_score(score: Any, failures: List[str]) -> None:
    if not isinstance(score, dict):
        failures.append("score must be a dict")
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
