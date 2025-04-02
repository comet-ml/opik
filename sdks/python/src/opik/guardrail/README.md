# Opik Guardrail

The Guardrail module provides a flexible framework for validating and transforming LLM outputs using Opik metrics. It offers a simple interface for orchestrating validation processes, handling retries, and executing success/failure hooks.

## Features

- **Validation**: Use Opik metrics to validate LLM outputs
- **Transformation**: Apply transformations to outputs before validation
- **Retry Logic**: Configurable retry mechanism with exponential backoff
- **Hooks**: Execute custom logic on validation success or failure
- **History Tracking**: Maintain a history of validation results
- **Tracing**: Built-in support for Opik's tracing capabilities

## Usage

### Basic Usage

```python
from opik.evaluation.metrics import Hallucination, AnswerRelevance
from opik.guardrail import Guardrail

# Create a guardrail with metrics
guardrail = Guardrail(
    metrics=[
        Hallucination(),
        AnswerRelevance()
    ]
)

# Validate an LLM output
result = await guardrail(
    llm_output="Your LLM response here",
    input_data={"question": "Your question here"}
)

if result.passed:
    print("Validation passed!")
    print(f"Scores: {result.scores}")
else:
    print("Validation failed!")
    print(f"Reasons: {result.reasons}")
```

### Using Transformations

```python
from opik.guardrail import Guardrail, Transformation
import json

# Create a transformation
json_parser = Transformation(
    transform_fn=lambda x: json.loads(x),
    name="json_parser"
)

# Create a guardrail with transformation
guardrail = Guardrail(
    metrics=[Hallucination()],
    transformations=[json_parser]
)

# Validate JSON output
result = await guardrail('{"answer": "Valid JSON response"}')
```

### Success/Failure Hooks

```python
async def on_success(result):
    print(f"Validation passed with scores: {result.scores}")

async def on_failure(result):
    print(f"Validation failed: {result.reasons}")

guardrail = Guardrail(
    metrics=[Hallucination()],
    on_success=on_success,
    on_failure=on_failure
)
```

### Retry Configuration

```python
guardrail = Guardrail(
    metrics=[Hallucination()],
    max_retries=3,
    retry_delay=1.0,  # seconds
    retry_backoff=2.0  # multiplier
)
```

## API Reference

### Guardrail

The main class for orchestrating validation and transformation of LLM outputs.

```python
class Guardrail:
    def __init__(
        self,
        metrics: List[BaseMetric],
        transformations: Optional[List[Transformation]] = None,
        on_success: Optional[Callable[[ValidationResult], Awaitable[None]]] = None,
        on_failure: Optional[Callable[[ValidationResult], Awaitable[None]]] = None,
        max_retries: int = 0,
        retry_delay: float = 1.0,
        retry_backoff: float = 2.0,
        name: Optional[str] = None,
        description: Optional[str] = None
    )
```

### Transformation

A class for defining transformations to be applied to data before validation.

```python
class Transformation:
    def __init__(
        self,
        transform_fn: Callable[[Any], Any],
        name: Optional[str] = None,
        description: Optional[str] = None
    )
```

### ValidationResult

A class that holds the results of a validation process.

```python
class ValidationResult:
    def __init__(
        self,
        passed: bool,
        scores: Dict[str, float],
        reasons: Optional[List[str]] = None,
        transformed_output: Optional[Any] = None,
        raw_output: Optional[Any] = None,
        metadata: Optional[Dict[str, Any]] = None
    )
```

## Contributing

Please refer to the main Opik project's contributing guidelines for information on how to contribute to this module. 