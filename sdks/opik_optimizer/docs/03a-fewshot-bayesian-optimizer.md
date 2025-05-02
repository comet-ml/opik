# FewShotBayesianOptimizer

## Overview

The FewShotBayesianOptimizer is a sophisticated prompt optimization tool that combines few-shot learning with Bayesian optimization techniques. It's designed to iteratively improve prompts by learning from examples and systematically exploring the optimization space.

## How It Works

1. **Initialization**
   - Takes a dataset of input-output pairs
   - Configures optimization parameters
   - Sets up evaluation metrics

2. **Bayesian Optimization**
   - Uses Gaussian Process to model the optimization space
   - Selects promising prompt configurations
   - Balances exploration and exploitation

3. **Few-shot Learning**
   - Dynamically selects relevant examples
   - Adapts to different problem types
   - Optimizes example selection

4. **Evaluation**
   - Multi-threaded performance testing
   - Comprehensive metrics tracking
   - Validation against test set

5. **Refinement**
   - Iterative improvement based on results
   - Adaptive parameter adjustment
   - Convergence monitoring

## Configuration Options

### Basic Configuration
```python
from opik.optimizer import FewShotBayesianOptimizer

optimizer = FewShotBayesianOptimizer(
    model="openai/gpt-4",  # or "azure/gpt-4"
    project_name="my-project",
    temperature=0.1,
    max_tokens=5000,
    num_threads=8,
    seed=42
)
```

### Advanced Configuration
```python
optimizer = FewShotBayesianOptimizer(
    model="openai/gpt-4",
    project_name="my-project",
    temperature=0.1,
    max_tokens=5000,
    num_threads=8,
    seed=42,
    min_examples=2,
    max_examples=8,
    n_initial_prompts=5,
    n_iterations=10,
    acquisition_function="ei",  # Expected Improvement
    kernel="matern",           # Kernel for Gaussian Process
    length_scale=1.0,          # Kernel length scale
    noise_level=0.1           # Observation noise level
)
```

## Example Usage

```python
from opik.optimizer import FewShotBayesianOptimizer

# Initialize optimizer
optimizer = FewShotBayesianOptimizer(
    model="openai/gpt-4",
    project_name="optimize-few-shot",
    temperature=0.1,
    max_tokens=5000
)

# Prepare dataset
dataset = [
    {"input": "example input 1", "output": "expected output 1"},
    {"input": "example input 2", "output": "expected output 2"},
    # ... more examples
]

# Run optimization
results = optimizer.optimize(
    dataset=dataset,
    num_trials=10
)

# Access results
print(f"Best prompt: {results.best_prompt}")
print(f"Improvement: {results.improvement_percentage}%")
print(f"Optimization history: {results.history}")
```

## Model Support

The FewShotBayesianOptimizer supports all models available through LiteLLM. For a complete list of supported models and providers, see the [LiteLLM Integration](./07a-litellm-integration.md) documentation.

### Common Providers
- OpenAI (gpt-4, gpt-3.5-turbo, etc.)
- Azure OpenAI
- Anthropic (Claude)
- Google (Gemini)
- Mistral
- Cohere

### Configuration Example
```python
optimizer = FewShotBayesianOptimizer(
    model="openai/gpt-4",  # or any LiteLLM supported model
    project_name="my-project",
    temperature=0.1,
    max_tokens=5000
)
```

## Best Practices

1. **Dataset Preparation**
   - Minimum 50 examples recommended
   - Diverse and representative samples
   - Clear input-output pairs

2. **Parameter Tuning**
   - Start with default parameters
   - Adjust based on problem complexity
   - Monitor convergence metrics

3. **Evaluation Strategy**
   - Use separate validation set
   - Track multiple metrics
   - Document optimization history

4. **Performance Optimization**
   - Adjust num_threads based on resources
   - Balance min_examples and max_examples
   - Monitor memory usage

## Research and References

- [Bayesian Optimization for Hyperparameter Tuning](https://arxiv.org/abs/1206.2944)
- [Few-shot Learning with Bayesian Optimization](https://arxiv.org/abs/1904.04232)
- [Gaussian Processes for Machine Learning](https://gaussianprocess.org/gpml/)

## Next Steps

- Learn about [MiproOptimizer](./03b-mipro-optimizer.md)
- Explore [Dataset Requirements](./04-datasets-and-testing.md)
- Check [Configuration Options](./05-configuration-and-usage.md) 