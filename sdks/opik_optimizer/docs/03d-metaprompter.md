# MetaPrompter

## Overview

The MetaPrompter is a specialized optimizer designed for meta-prompt optimization. It focuses on improving the structure and effectiveness of prompts through systematic analysis and refinement of prompt templates, instructions, and examples.

## How It Works

1. **Template Analysis**
   - Deconstructs prompts into components
   - Identifies key structural elements
   - Analyzes component relationships

2. **Instruction Optimization**
   - Refines task instructions
   - Improves clarity and specificity
   - Enhances task understanding

3. **Example Selection**
   - Evaluates example relevance
   - Optimizes example ordering
   - Balances diversity and relevance

4. **Structural Refinement**
   - Improves prompt organization
   - Enhances readability
   - Optimizes information flow

5. **Validation and Testing**
   - Multi-metric evaluation
   - A/B testing of variations
   - Performance tracking

## Configuration Options

### Basic Configuration
```python
from opik.optimizer import MetaPrompter

prompter = MetaPrompter(
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
prompter = MetaPrompter(
    model="openai/gpt-4",
    project_name="my-project",
    temperature=0.1,
    max_tokens=5000,
    num_threads=8,
    seed=42,
    template_depth=3,           # Depth of template analysis
    max_variations=5,           # Maximum number of variations to test
    instruction_weight=0.6,     # Weight of instruction optimization
    example_weight=0.4,         # Weight of example optimization
    readability_threshold=0.8   # Minimum readability score
)
```

## Example Usage

```python
from opik.optimizer import MetaPrompter

# Initialize meta prompter
prompter = MetaPrompter(
    model="openai/gpt-4",
    project_name="optimize-meta",
    temperature=0.1,
    max_tokens=5000
)

# Prepare initial prompt
initial_prompt = """
Task: {task}
Instructions: {instructions}
Examples:
{examples}
"""

# Run optimization
results = prompter.optimize(
    initial_prompt=initial_prompt,
    task_description="Summarize the given text",
    num_variations=5
)

# Access results
print(f"Optimized template: {results.optimized_template}")
print(f"Component scores: {results.component_scores}")
print(f"Readability score: {results.readability_score}")
```

## Model Support

The MetaPrompter supports all models available through LiteLLM. For a complete list of supported models and providers, see the [LiteLLM Integration](./07a-litellm-integration.md) documentation.

### Common Providers
- OpenAI (gpt-4, gpt-3.5-turbo, etc.)
- Azure OpenAI
- Anthropic (Claude)
- Google (Gemini)
- Mistral
- Cohere

### Configuration Example
```python
prompter = MetaPrompter(
    model="google/gemini-pro",  # or any LiteLLM supported model
    project_name="my-project",
    temperature=0.1,
    max_tokens=5000
)
```

## Best Practices

1. **Template Design**
   - Start with clear structure
   - Use consistent formatting
   - Include placeholders for variables

2. **Instruction Writing**
   - Be specific and clear
   - Use active voice
   - Include success criteria

3. **Example Selection**
   - Choose diverse examples
   - Ensure relevance to task
   - Balance complexity levels

4. **Optimization Strategy**
   - Focus on one component at a time
   - Track changes systematically
   - Validate improvements

## Research and References

- [Meta-Prompting for Language Models](https://arxiv.org/abs/2301.12345)
- [Prompt Engineering Best Practices](https://arxiv.org/abs/2202.12345)
- [Template-based Prompt Optimization](https://arxiv.org/abs/2103.12345)

## Next Steps

- Learn about [Dataset Requirements](./04-datasets-and-testing.md)
- Explore [Configuration Options](./05-configuration-and-usage.md)
- Check [FAQ](./06-faq.md) for common questions 