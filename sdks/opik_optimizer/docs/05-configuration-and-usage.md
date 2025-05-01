# Configuration and Usage

## Overview
Proper configuration and usage of the Opik Optimizer are essential for achieving optimal results. This section provides detailed instructions on setting up and using the optimizer effectively.

## Model Configuration

### Supported Models

#### OpenAI Models
```python
# For OpenAI models
model="openai/gpt-4"        # GPT-4
model="openai/gpt-3.5-turbo" # GPT-3.5 Turbo
```

#### Azure OpenAI Models
```python
# For Azure OpenAI models
model="azure/gpt-4"         # Azure GPT-4
model="azure/gpt-3.5-turbo"  # Azure GPT-3.5 Turbo
```

### Model Parameters

```python
optimizer = FewShotOptimizer(
    model="openai/gpt-4",  # Model identifier
    temperature=0.1,       # Controls randomness (0.0 to 1.0)
    max_tokens=5000,      # Maximum tokens per response
    top_p=1.0,           # Nucleus sampling parameter
    frequency_penalty=0.0, # Frequency penalty
    presence_penalty=0.0  # Presence penalty
)
```

## Project Setup

### Basic Configuration
```python
from opik.optimizer import FewShotOptimizer

optimizer = FewShotOptimizer(
    model="openai/gpt-4",
    project_name="my-optimization-project",
    temperature=0.1,
    max_tokens=5000
)
```

### Advanced Configuration
```python
optimizer = FewShotOptimizer(
    model="openai/gpt-4",
    project_name="advanced-project",
    temperature=0.1,
    max_tokens=5000,
    num_examples=5,        # Number of examples to use
    num_trials=10,         # Number of optimization trials
    multi_agent=True,      # Enable multi-agent optimization
    validation_split=0.2,  # Validation set size
    metrics=["accuracy", "f1"]  # Evaluation metrics
)
```

## Running Optimizations

### Basic Usage
```python
# Initialize optimizer
optimizer = FewShotOptimizer(
    model="openai/gpt-4",
    project_name="basic-usage"
)

# Prepare dataset
dataset = [
    {"input": "example 1", "output": "expected 1"},
    {"input": "example 2", "output": "expected 2"}
]

# Run optimization
results = optimizer.optimize(
    dataset=dataset,
    num_trials=10
)
```

### Advanced Usage
```python
# Initialize with advanced settings
optimizer = FewShotOptimizer(
    model="openai/gpt-4",
    project_name="advanced-usage",
    temperature=0.1,
    max_tokens=5000,
    multi_agent=True
)

# Run optimization with validation
results = optimizer.optimize(
    dataset=training_data,
    validation_data=validation_data,
    num_trials=20,
    metrics=["accuracy", "precision", "recall"]
)

# Access results
print(f"Best prompt: {results.best_prompt}")
print(f"Training Accuracy: {results.training_accuracy}")
print(f"Validation Accuracy: {results.validation_accuracy}")
```

## Best Practices

### Model Selection
1. **Choose Appropriate Model**
   - GPT-4 for complex tasks.
   - GPT-3.5 for simpler tasks.
   - Consider cost and performance.

2. **Parameter Tuning**
   - Start with default values.
   - Adjust based on results.
   - Document changes for future reference.

### Project Organization
1. **Naming Conventions**
   - Use descriptive project names.
   - Include version information.
   - Document configurations.

2. **Data Management**
   - Organize datasets.
   - Maintain validation sets.
   - Track experiments.

### Optimization Strategy
1. **Trial Configuration**
   - Start with fewer trials.
   - Increase based on results.
   - Monitor progress.

2. **Evaluation**
   - Use multiple metrics.
   - Compare against baselines.
   - Document improvements.

## Common Configuration Examples

### Azure OpenAI Setup
```python
optimizer = FewShotOptimizer(
    model="azure/gpt-4",
    project_name="azure-project",
    temperature=0.1,
    max_tokens=5000
)
```

### Multi-agent Optimization
```python
optimizer = FewShotOptimizer(
    model="openai/gpt-4",
    project_name="multi-agent-project",
    multi_agent=True,
    num_agents=3,  # Number of agents to use
    temperature=0.1
)
```

### Custom Metrics
```python
optimizer = FewShotOptimizer(
    model="openai/gpt-4",
    project_name="custom-metrics",
    metrics=["accuracy", "f1", "custom_metric"],
    custom_metric_function=my_custom_metric
)
```

## Next Steps

- Check [FAQ](./06-faq.md) for common questions and troubleshooting tips.
- Explore [API Reference](./07-api-reference.md) for detailed technical documentation.
- Review [Optimizers](./03-optimizers.md) for algorithm-specific information. 