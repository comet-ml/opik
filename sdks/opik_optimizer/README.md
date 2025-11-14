# Opik Agent Optimizer

[![PyPI version](https://img.shields.io/pypi/v/opik-optimizer.svg)](https://pypi.org/project/opik-optimizer/)
[![Python versions](https://img.shields.io/pypi/pyversions/opik-optimizer.svg)](https://pypi.org/project/opik-optimizer/)
[![Downloads](https://static.pepy.tech/badge/opik-optimizer)](https://pepy.tech/project/opik-optimizer)
[![License](https://img.shields.io/github/license/comet-ml/opik)](https://github.com/comet-ml/opik/blob/main/LICENSE)

The Opik Agent Optimizer refines your prompts to achieve better performance from your Large Language Models (LLMs). It supports a variety of optimization algorithms, all with a **standardized API** for consistent usage and chaining:

* **EvolutionaryOptimizer** - Uses genetic algorithms for prompt evolution
* **FewShotBayesianOptimizer** - Uses few-shot learning with Bayesian optimization
* **MetaPromptOptimizer** - Employs meta-prompting techniques for optimization
* **MiproOptimizer** - Implements MIPRO (Multi-Input Prompt Optimization) algorithm
* **GepaOptimizer** - Leverages GEPA (Genetic-Pareto) optimization approach
* **ParameterOptimizer** - Optimizes LLM call parameters (temperature, top_p, etc.) using Bayesian optimization

## 🎯 Key Features

- **Standardized API**: All optimizers follow the same interface for `optimize_prompt()` and `optimize_mcp()` methods
- **Optimizer Chaining**: Results from one optimizer can be used as input for another
- **MCP Support**: Built-in support for Model Context Protocol tool calling
- **Consistent Results**: All optimizers return standardized `OptimizationResult` objects
- **Counter Tracking**: Built-in LLM and tool call counters for monitoring usage
- **Type Safety**: Full type hints and validation for robust development
- **Backward Compatibility**: All original parameters preserved through kwargs extraction
- **Deprecation Warnings**: Clear warnings for deprecated parameters with migration guidance

Opik Optimizer is a component of the [Opik platform](https://github.com/comet-ml/opik), an open-source LLM evaluation platform by Comet.
For more information about the broader Opik ecosystem, visit our [Website](https://www.comet.com/site/products/opik/) or [Documentation](https://www.comet.com/docs/opik/).

## Quickstart

Explore Opik Optimizer's capabilities with our interactive notebook:

<a href="https://colab.research.google.com/github/comet-ml/opik/blob/main/sdks/opik_optimizer/notebooks/OpikOptimizerIntro.ipynb">
  <img src="https://colab.research.google.com/assets/colab-badge.svg" alt="Open in Colab"/>
</a>

## Setup

To get started with Opik Optimizer, follow these steps:

1.  **Install the package:**
    ```bash
    # using pip
    pip install opik-optimizer

    # using uv (faster)
    uv pip install opik-optimizer
    ```

2.  **Configure Opik (Optional, for advanced features):**
    If you plan to log optimization experiments to Comet or use Opik Datasets, you'll need to configure the Opik client:
    ```bash
    # Install the main Opik CLI (if not already installed)
    pip install opik

    # Configure your Comet API key and workspace
    opik configure
    # When prompted, enter your Opik API key and workspace details.
    ```
    Using Opik with Comet allows you to track your optimization runs, compare results, and manage datasets seamlessly.

3.  **Set up LLM Provider API Keys:**
    Ensure your environment variables are set for the LLM(s) you intend to use. For example, for OpenAI models:
    ```bash
    export OPENAI_API_KEY="your_openai_api_key"
    ```
    The optimizer utilizes LiteLLM, so you can configure keys for various providers as per LiteLLM's documentation.

You'll typically need:

*   An LLM model name (e.g., "gpt-4o-mini", "claude-3-haiku-20240307").
*   An [Opik Dataset](https://www.comet.com/docs/opik/evaluation/manage_datasets/) (or a compatible local dataset/data generator).
*   An [Opik Metric](https://www.comet.com/docs/opik/evaluation/metrics/overview/) (or a custom evaluation function).
*   A starting prompt (template string).

## Standardized API

All optimizers follow the same interface, making it easy to switch between algorithms or chain them together:

```python
# All optimizers have the same signature
def optimize_prompt(
    self,
    prompt: ChatPrompt,
    dataset: Dataset,
    metric: Callable,
    experiment_config: dict | None = None,
    n_samples: int | None = None,
    auto_continue: bool = False,
    agent_class: type[OptimizableAgent] | None = None,
    **kwargs: Any,
) -> OptimizationResult

# All optimizers return the same result type
result = optimizer.optimize_prompt(
    prompt=prompt,
    dataset=dataset,
    metric=metric,
    n_samples=100
)

# Results can be chained
chained_result = another_optimizer.optimize_prompt(
    prompt=ChatPrompt.from_result(result),  # Use previous result
    dataset=dataset,
    metric=metric
)
```

## Example

Here's a brief example of how to use the `FewShotBayesianOptimizer`. We'll use a sample dataset provided by Opik.

Available sample datasets for testing:
*   `"tiny-test"`
*   `"halu-eval-300"`
*   `"hotpot-300"`

```python
from opik.evaluation.metrics import LevenshteinRatio
from opik_optimizer import FewShotBayesianOptimizer, ChatPrompt
from opik_optimizer.datasets import hotpot_300

# Load a sample dataset
hot_pot_dataset = hotpot_300()

project_name = "optimize-few-shot-bayesian-hotpot" # For Comet logging

# Define the instruction for your chat prompt.
# Input parameters from dataset examples will be interpolated into the full prompt.
prompt = ChatPrompt(
    project_name=project_name,
    messages=[
        {"role": "system", "content": "You are a helpful assistant."},
        {"role": "user", "content": "{question}"}
    ]
)

optimizer = FewShotBayesianOptimizer(
    model="gpt-4o-mini", # LiteLLM name to use for generation and optimization
    min_examples=3,      # Min few-shot examples
    max_examples=8,      # Max few-shot examples
    n_threads=16,        # Parallel threads for evaluation
    seed=42,
)

def levenshtein_ratio(dataset_item, llm_output):
    return LevenshteinRatio().score(reference=dataset_item["answer"], output=llm_output)

# Run the optimization
result = optimizer.optimize_prompt(
    prompt=prompt,
    dataset=hot_pot_dataset,
    metric=levenshtein_ratio,
    n_samples=150  # Number of dataset samples for evaluation per trial
)

# Display the best prompt and its score
result.display()
```
The `result` object contains the optimized prompt, evaluation scores, and other details from the optimization process. If `project_name` is provided and Opik is configured, results will also be logged to your Comet workspace.

The optimizer automatically logs run metadata—including optimizer version, tool schemas, prompt messages, and the models used—so you get consistent experiment context without any additional arguments. If you still need custom tags (for example identifying the dataset or task), pass an `experiment_config` dictionary and your fields will be merged on top of the defaults.

## Tool Optimization (MCP) - Beta

The Opik Agent Optimizer supports **true tool optimization** for MCP (Model Context Protocol) tools. This feature is currently in **Beta** and supported by the **MetaPrompt Optimizer**.

### Key Features

- **MCP Tool Optimization** - Optimize MCP tool descriptions and usage patterns (Beta)
- **Tool-Aware Analysis** - The optimizer understands MCP tool schemas and usage patterns
- **Multi-step Workflow Support** - Optimize complex agent workflows involving MCP tools

### Agent Function Calling (Not Tool Optimization)

Many optimizers can optimize **agents that use function calling**, but this is different from true tool optimization. Here's an example with GEPA:

```python
from opik_optimizer import GepaOptimizer, ChatPrompt

# GEPA example: optimizing an agent with function calling
prompt = ChatPrompt(
    system="You are a helpful assistant. Use the search_wikipedia tool when needed.",
    user="{question}",
    tools=[
        {
            "type": "function",
            "function": {
                "name": "search_wikipedia",
                "description": "This function searches Wikipedia abstracts.",
                "parameters": {
                    "type": "object",
                    "properties": {
                        "query": {"type": "string", "description": "Search query"}
                    },
                    "required": ["query"]
                }
            }
        }
    ],
    function_map={
        "search_wikipedia": lambda query: search_wikipedia(query, use_api=True)
    }
)

# GEPA optimizes the agent's prompt, not the tools themselves
optimizer = GepaOptimizer(model="gpt-4o-mini")
result = optimizer.optimize_prompt(prompt=prompt, dataset=dataset, metric=metric)
```

### True Tool Optimization (MCP) - Beta

```python
from opik_optimizer import MetaPromptOptimizer

# MCP tool optimization is currently in Beta
# See scripts/litellm_metaprompt_context7_mcp_example.py for working examples
optimizer = MetaPromptOptimizer(model="gpt-4")
# MCP tools are configured through mcp.json manifests
```

For comprehensive documentation on tool optimization, see the [Tool Optimization Guide](https://www.comet.com/docs/opik/agent_optimization/algorithms/tool_optimization).

## Deprecation Warnings

The following parameters are deprecated and will be removed in future versions:

### Constructor Parameters

- **`project_name`** in optimizer constructors: Set `project_name` in the `ChatPrompt` instead
- **`num_threads`** in optimizer constructors: Use `n_threads` instead

### Example Migration

```python
# ❌ Deprecated
optimizer = FewShotBayesianOptimizer(
    model="gpt-4o-mini",
    project_name="my-project",  # Deprecated
    num_threads=16,             # Deprecated
)

# ✅ Correct
optimizer = FewShotBayesianOptimizer(
    model="gpt-4o-mini",
    n_threads=16,  # Use n_threads instead
)

prompt = ChatPrompt(
    project_name="my-project",  # Set here instead
    messages=[...]
)
```

### Suppressing Deprecation Warnings

To suppress deprecation warnings during development:

```python
import warnings
warnings.filterwarnings("ignore", category=DeprecationWarning)
```

### MCP Integration (Beta)

The optimizer includes utilities for MCP tool integration:

```bash
# Install MCP Python SDK
pip install mcp

# Run MCP examples (Beta)
python scripts/litellm_metaprompt_context7_mcp_example.py
```

Underlying utilities are available in `src/opik_optimizer/utils/{prompt_segments,mcp,mcp_simulator}.py`.

<Note>
  **Important:** True tool optimization (MCP) is currently in **Beta**. Most examples show **agent optimization** (optimizing prompts for agents that use tools), which is different from optimizing the tools themselves.
</Note>

## Development

To contribute or use the Opik Optimizer from source:

1.  **Clone the Opik repository:**
    ```bash
    git clone git@github.com:comet-ml/opik.git
    ```
2.  **Navigate to the optimizer's directory:**
    ```bash
    cd opik/sdks/opik_optimizer  # Adjust 'opik' if you cloned into a different folder name
    ```
3.  **Install in editable mode (with development dependencies):**
    ```bash
    pip install -e .[dev]
    ```
    The `[dev]` extra installs dependencies useful for development, such as `pytest`.

## Requirements

- **Python `>=3.10,<3.13`** (see [Python version requirements](https://github.com/comet-ml/opik/pull/3373))
- Opik API key (recommended for full functionality, configure via `opik configure`)
- API key for your chosen LLM provider (e.g., OpenAI, Anthropic, Gemini), configured as per LiteLLM guidelines.
