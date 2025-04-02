# Opik Guardrail

The Guardrail module provides a flexible framework for validating and transforming LLM outputs using Opik metrics. It offers a simple interface for orchestrating validation processes, handling retries, and executing success/failure hooks.

## Features

- **Validation**: Use Opik metrics to validate LLM outputs
- **Transformation**: Apply transformations to outputs before validation
- **Retry Logic**: Configurable retry mechanism with exponential backoff
- **Hooks**: Execute custom logic on validation success or failure
- **History Tracking**: Maintain a history of validation results
- **Tracing**: Built-in support for Opik's tracking capabilities

## Usage

### Basic Usage

```python
from opik.guardrail import Guardrail
from opik.evaluation.metrics import Hallucination, AnswerRelevance

def all_metrics_pass(results):
    """Check if all metrics passed."""
    return all(result.get("passed", True) for result in results)

# Create a guardrail with metrics
guardrail = Guardrail(
    metrics=[
        Hallucination(),
        AnswerRelevance()
    ],
    calculate_success=all_metrics_pass
)

# Validate an LLM output
result = await guardrail(
    llm_output="Your LLM response here",
    input_data={"question": "Your question here"}
)

if result.passed:
    print("Validation passed!")
    print(f"Scores: {result.scores}")
    print(f"Metadata: {result.metadata}")
else:
    print("Validation failed!")
    print(f"Reasons: {result.reasons}")
```

### Using Transformations

```python
from opik.guardrail import Guardrail, Transformation
from opik.evaluation.metrics import Hallucination
import json

def json_and_metrics_pass(results):
    """Check if JSON is valid and all metrics passed."""
    return all(result.get("passed", True) for result in results)

# Create a transformation
json_parser = Transformation(
    transform_fn=lambda x: json.loads(x),
    name="json_parser"
)

# Create a guardrail with transformation and metrics
guardrail = Guardrail(
    metrics=[Hallucination()],
    calculate_success=json_and_metrics_pass,
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

def all_metrics_pass(results):
    """Check if all metrics passed."""
    return all(result.get("passed", True) for result in results)

guardrail = Guardrail(
    metrics=[Hallucination()],
    calculate_success=all_metrics_pass,
    on_success=on_success,
    on_failure=on_failure
)
```

### Retry Configuration

```python
def all_metrics_pass(results):
    """Check if all metrics passed."""
    return all(result.get("passed", True) for result in results)

guardrail = Guardrail(
    metrics=[Hallucination()],
    calculate_success=all_metrics_pass,
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
        calculate_success: Callable[[List[ScoreResult]], bool],
        transformations: Optional[List[Transformation]] = None,
        on_success: Optional[Callable[[ValidationResult], None]] = None,
        on_failure: Optional[Callable[[ValidationResult], None]] = None,
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
        reasons: Optional[Dict[str, str]] = None,
        transformed_output: Optional[Any] = None,
        raw_output: Optional[Any] = None,
        metadata: Optional[Dict[str, Any]] = None
    )
```

## Examples

### Topic Filtering with LLM-based Validation

This example shows how to create a Guardrail that prevents responses about specific topics using an LLM-based metric:

```python
from typing import Dict, Any
import openai
from opik.guardrail import Guardrail
from opik.evaluation.metrics import BaseMetric

class TopicFilterMetric(BaseMetric):
    """A metric that uses an LLM to check if a response contains content about a specific topic."""
    
    def __init__(self, topic: str, api_key: str):
        self.topic = topic
        self.api_key = api_key
        super().__init__(name=f"topic_filter_{topic}")
    
    async def score(self, output: str, **kwargs) -> Dict[str, Any]:
        """Score the output by checking if it contains content about the restricted topic."""
        client = openai.AsyncOpenAI(api_key=self.api_key)
        
        prompt = f"""Analyze the following text and determine if it contains content about {self.topic}.
        Return a JSON object with two fields:
        - 'contains_topic': boolean indicating if the text contains content about {self.topic}
        - 'reason': string explaining your decision
        
        Text to analyze: {output}"""
        
        response = await client.chat.completions.create(
            model="gpt-3.5-turbo",
            messages=[{"role": "user", "content": prompt}],
            response_format={"type": "json_object"}
        )
        
        result = json.loads(response.choices[0].message.content)
        return {
            "value": 0.0 if result["contains_topic"] else 1.0,
            "passed": not result["contains_topic"],
            "metadata": {
                "contains_topic": result["contains_topic"],
                "reason": result["reason"]
            }
        }

def no_topics_detected(results):
    """Check if any topic was detected."""
    return all(
        not result.get("metadata", {}).get("contains_topic", False)
        for result in results
    )

# Create a guardrail that filters out F1 racing content
guardrail = Guardrail(
    metrics=[
        TopicFilterMetric(
            topic="Formula 1 racing",
            api_key="your-openai-api-key"
        )
    ],
    calculate_success=no_topics_detected,
    on_failure=lambda result: print(f"Response blocked: {result.reasons['topic_filter_Formula 1 racing']}")
)

# Test the guardrail
result = await guardrail(
    llm_output="The Monaco Grand Prix is one of the most prestigious races in Formula 1...",
    input_data={}
)

if not result.passed:
    print("Response was blocked due to F1 content!")
```

This example demonstrates:
1. Creating a custom metric that uses OpenAI's API to check for topic content
2. Defining a success calculation callback
3. Configuring the guardrail with the topic filter metric
4. Setting up a failure hook to handle blocked responses
5. Testing the guardrail with content that should be blocked

The `TopicFilterMetric` uses GPT-3.5-turbo to analyze the content and determine if it contains information about the restricted topic. The metric:
- Returns a score of 0.0 if the topic is detected
- Returns a score of 1.0 if the topic is not detected
- Stores the detection result and reason in the metadata

The `no_topics_detected` callback implements the success calculation by checking if any topic was detected across all metrics.

You can extend this example by:
1. Adding multiple topic filters
2. Implementing more sophisticated topic detection
3. Adding additional metrics for other types of content filtering
4. Customizing the failure handling behavior

## Contributing

Please refer to the main Opik project's contributing guidelines for information on how to contribute to this module. 