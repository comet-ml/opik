# Python SDK Error Handling

## Exception Hierarchy

All custom exceptions inherit from `OpikException`:

```python
from opik.exceptions import (
    OpikException,          # Base for all Opik errors
    ConfigurationError,     # Invalid configuration
    MetricComputationError, # Metric computation failed
    GuardrailValidationFailed,  # Guardrail check failed
    ScoreMethodMissingArguments,  # Score method missing args
    OpikCloudRequestsRateLimited,  # Rate limited
)
```

## MetricComputationError

**Always raise** `MetricComputationError` from `BaseMetric` subclasses instead of hiding errors:

```python
from opik.evaluation.metrics import BaseMetric
from opik.exceptions import MetricComputationError

class MyMetric(BaseMetric):
    def score(self, text: str) -> ScoreResult:
        try:
            # computation logic
            return ScoreResult(value=result)
        except Exception as e:
            # ✅ GOOD - Raise MetricComputationError
            raise MetricComputationError(f"Failed to compute metric: {e}")

            # ❌ BAD - Don't hide errors
            # return ScoreResult(value=0.0)
```

## Structured Exception Information

```python
class ScoreMethodMissingArguments(OpikException):
    def __init__(
        self,
        score_name: str,
        missing_required_arguments: Sequence[str],
        available_keys: Sequence[str],
    ):
        self.score_name = score_name
        self.missing_required_arguments = missing_required_arguments
        self.available_keys = available_keys
        super().__init__(self._get_error_message())

    def _get_error_message(self) -> str:
        return (
            f"The scoring method {self.score_name} is missing arguments: "
            f"{self.missing_required_arguments}. Available keys: {self.available_keys}."
        )
```

## HTTP Error Handling

```python
try:
    response = self._rest_client.call(...)
except rest_api_core.ApiError as e:
    if e.status_code == 409:
        # Conflict - duplicate request, ignore
        return
    elif e.status_code == 429:
        # Rate limited - handle retry
        raise OpikCloudRequestsRateLimited(...)
    else:
        LOGGER.error("API call failed: %s", str(e))
        raise
```

## Best Practices

- ✅ Use specific exception types
- ✅ Inherit from `OpikException`
- ✅ Include meaningful error messages with context
- ✅ Handle rate limiting with retries
- ❌ Don't catch generic `Exception` without re-raising
- ❌ Don't hide errors in metric computations
