# API Reference

## Overview
This API reference provides detailed information about the classes and methods available in the Opik Optimizer, helping developers integrate and utilize the tool effectively.

## FewShotOptimizer

### Class Definition
```python
class FewShotOptimizer(
    model: str,
    project_name: str,
    temperature: float = 0.1,
    max_tokens: int = 5000,
    num_examples: int = 5,
    num_trials: int = 10,
    multi_agent: bool = False,
    validation_split: float = 0.2,
    metrics: List[str] = ["accuracy"],
    **kwargs
)
```

### Parameters

#### Required Parameters
- `model` (str): Model identifier (e.g., "openai/gpt-4", "azure/gpt-4")
- `project_name` (str): Name of the optimization project

#### Optional Parameters
- `temperature` (float): Controls randomness (0.0 to 1.0, default: 0.1)
- `max_tokens` (int): Maximum tokens per response (default: 5000)
- `num_examples` (int): Number of examples to use (default: 5)
- `num_trials` (int): Number of optimization trials (default: 10)
- `multi_agent` (bool): Enable multi-agent optimization (default: False)
- `validation_split` (float): Validation set size (default: 0.2)
- `metrics` (List[str]): Evaluation metrics (default: ["accuracy"])
- `**kwargs`: Additional model-specific parameters

### Methods

#### optimize
```python
def optimize(
    self,
    dataset: List[Dict[str, str]],
    validation_data: Optional[List[Dict[str, str]]] = None,
    num_trials: Optional[int] = None,
    metrics: Optional[List[str]] = None,
    **kwargs
) -> OptimizationResults
```

Optimizes prompts using the provided dataset.

**Parameters:**
- `dataset` (List[Dict[str, str]]): Training dataset
- `validation_data` (Optional[List[Dict[str, str]]]): Validation dataset
- `num_trials` (Optional[int]): Number of trials to run
- `metrics` (Optional[List[str]]): Evaluation metrics
- `**kwargs`: Additional optimization parameters

**Returns:**
- `OptimizationResults`: Object containing optimization results

#### evaluate
```python
def evaluate(
    self,
    prompt: str,
    dataset: List[Dict[str, str]],
    metrics: Optional[List[str]] = None
) -> Dict[str, float]
```

Evaluates a prompt against a dataset.

**Parameters:**
- `prompt` (str): Prompt to evaluate
- `dataset` (List[Dict[str, str]]): Evaluation dataset
- `metrics` (Optional[List[str]]): Evaluation metrics

**Returns:**
- `Dict[str, float]`: Dictionary of metric scores

## OptimizationResults

### Class Definition
```python
class OptimizationResults:
    best_prompt: str
    training_accuracy: float
    validation_accuracy: float
    improvement_percentage: float
    trial_results: List[Dict[str, Any]]
```

### Attributes
- `best_prompt` (str): The best performing prompt
- `training_accuracy` (float): Accuracy on training data
- `validation_accuracy` (float): Accuracy on validation data
- `improvement_percentage` (float): Percentage improvement
- `trial_results` (List[Dict[str, Any]]): Results from all trials

## Dataset Format

### Training/Validation Dataset
```python
dataset = [
    {
        "input": "Input text or query",
        "output": "Expected output or response"
    },
    # ... more examples
]
```

## Model Parameters

### OpenAI/Azure OpenAI Parameters
```python
{
    "temperature": 0.1,        # Controls randomness
    "max_tokens": 5000,       # Maximum tokens per response
    "top_p": 1.0,            # Nucleus sampling
    "frequency_penalty": 0.0, # Frequency penalty
    "presence_penalty": 0.0   # Presence penalty
}
```

## Error Handling

### Common Exceptions
- `ModelError`: Errors related to model configuration
- `DatasetError`: Errors related to dataset format or content
- `OptimizationError`: Errors during optimization process
- `ValidationError`: Errors during validation

### Example Error Handling
```python
try:
    results = optimizer.optimize(dataset=my_dataset)
except ModelError as e:
    print(f"Model configuration error: {e}")
except DatasetError as e:
    print(f"Dataset error: {e}")
except OptimizationError as e:
    print(f"Optimization error: {e}")
```

## Examples

### Basic Usage
```python
from opik.optimizer import FewShotOptimizer

# Initialize optimizer
optimizer = FewShotOptimizer(
    model="openai/gpt-4",
    project_name="basic-example"
)

# Prepare dataset
dataset = [
    {"input": "example 1", "output": "expected 1"},
    {"input": "example 2", "output": "expected 2"}
]

# Run optimization
results = optimizer.optimize(dataset=dataset)
```

### Advanced Usage
```python
from opik.optimizer import FewShotOptimizer

# Initialize with advanced settings
optimizer = FewShotOptimizer(
    model="azure/gpt-4",
    project_name="advanced-example",
    temperature=0.1,
    max_tokens=5000,
    multi_agent=True
)

# Run optimization with validation
results = optimizer.optimize(
    dataset=training_data,
    validation_data=validation_data,
    num_trials=20,
    metrics=["accuracy", "f1"]
)
```

## Next Steps

- Review [Configuration and Usage](./05-configuration-and-usage.md) for setup guides.
- Check [FAQ](./06-faq.md) for common questions and troubleshooting tips.
- Explore [Optimizers](./03-optimizers.md) for algorithm-specific information. 