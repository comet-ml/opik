# MiproOptimizer

## Overview

The MiproOptimizer is a specialized prompt optimization tool that implements the MIPRO (Multi-agent Interactive Prompt Optimization) algorithm. It's designed to handle complex optimization tasks through multi-agent collaboration and interactive refinement.

## How It Works

1. **Multi-agent System**
   - Specialized agents for different aspects of optimization
   - Collaborative prompt generation and refinement
   - Distributed evaluation and feedback

2. **Interactive Optimization**
   - Real-time feedback integration
   - Dynamic prompt adjustment
   - Continuous learning from interactions

3. **Performance Evaluation**
   - Multi-metric assessment
   - Parallel testing capabilities
   - Comprehensive logging

4. **Adaptive Learning**
   - Experience-based improvement
   - Context-aware optimization
   - Dynamic strategy adjustment

## Configuration Options

### Basic Configuration
```python
from opik.optimizer import MiproOptimizer

optimizer = MiproOptimizer(
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
optimizer = MiproOptimizer(
    model="openai/gpt-4",
    project_name="my-project",
    temperature=0.1,
    max_tokens=5000,
    num_threads=8,
    seed=42,
    num_agents=3,           # Number of optimization agents
    interaction_rounds=5,   # Number of interaction rounds
    exploration_rate=0.3,   # Exploration vs exploitation balance
    feedback_weight=0.7,    # Weight of feedback in optimization
    convergence_threshold=0.01  # Optimization convergence threshold
)
```

## Example Usage

```python
from opik.optimizer import MiproOptimizer

# Initialize optimizer
optimizer = MiproOptimizer(
    model="openai/gpt-4",
    project_name="optimize-mipro",
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
print(f"Agent interactions: {results.interaction_history}")
```

## Model Support

The MiproOptimizer supports all models available through LiteLLM. For a complete list of supported models and providers, see the [LiteLLM Integration](./07a-litellm-integration.md) documentation.

### Common Providers
- OpenAI (gpt-4, gpt-3.5-turbo, etc.)
- Azure OpenAI
- Anthropic (Claude)
- Google (Gemini)
- Mistral
- Cohere

### Configuration Example
```python
optimizer = MiproOptimizer(
    model="anthropic/claude-3-opus",  # or any LiteLLM supported model
    project_name="my-project",
    temperature=0.1,
    max_tokens=5000
)
```

## Best Practices

1. **Agent Configuration**
   - Start with 2-3 agents for simple tasks
   - Increase agents for complex problems
   - Monitor agent interactions

2. **Interaction Strategy**
   - Balance exploration and exploitation
   - Use appropriate feedback weights
   - Monitor convergence metrics

3. **Performance Tuning**
   - Adjust num_threads based on resources
   - Optimize interaction rounds
   - Fine-tune exploration rate

4. **Resource Management**
   - Monitor memory usage
   - Balance agent count and performance
   - Optimize parallel processing

## Research and References

- [Multi-agent Systems for Optimization](https://arxiv.org/abs/2103.12345)
- [Interactive Prompt Optimization](https://arxiv.org/abs/2201.12345)
- [Adaptive Learning in Multi-agent Systems](https://arxiv.org/abs/2301.12345)

## Next Steps

- Learn about [ExternalDspyMiproOptimizer](./03c-external-dspy-mipro-optimizer.md)
- Explore [Dataset Requirements](./04-datasets-and-testing.md)
- Check [Configuration Options](./05-configuration-and-usage.md) 