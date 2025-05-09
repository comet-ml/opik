# Opik Optimizer

The Opik Opitmizer can refine your prompts to get better performance
from your LLMs. You can use a variety of algorithms, including:

* FewShotBayesianOptimizer
* MiproOptimizer
* MetaPromptOptimizer

## Quickstart


[Open Quickstart Notebook in Colab](https://colab.research.google.com/github/comet-ml/opik/blob/main/sdks/opik_optimizer/notebooks/OpikOptimizerIntro.ipynb)


## Setup

1. Configure Opik:
   ```bash
   # Install Comet ML CLI
   pip install opik

   # Configure your API key
   opik configure
   # When prompted, enter your Opik API key
   ```

2. Set up your environment variables:
   ```bash
   # OpenAI API key for LLM access
   export OPENAI_API_KEY=your_openai_api_key
   ```

3. Install the package:
   ```bash
   pip install opik-optimizer
   ```

You'll need:

1. An LLM model name
2. An Opik Dataset (or Opik Dataset name)
3. An Opik Metric (possibly a custom one)
4. A starting prompt (string)

## Example

We have prepared some sample datasets for testing:

* "tiny-test"
* "halu-eval-300"
* "hotpot-300"

You can see how to use those below:

```python
from opik.evaluation.metrics import LevenshteinRatio
from opik_optimizer import FewShotBayesianOptimizer
from opik_optimizer.demo import get_or_create_dataset

from opik_optimizer import (
    MetricConfig,
    TaskConfig,
    from_dataset_field,
    from_llm_response_text,
)

hot_pot_dataset = get_or_create_dataset("hotpot-300")

# For chat prompts instruction doesn't need to contain input parameters from dataset examples.
prompt_instruction = """
Answer the question.
"""
project_name = "optimize-few-shot-bayesian-hotpot"

optimizer = FewShotBayesianOptimizer(
    model="gpt-4o-mini",
    project_name=project_name,
    min_examples=3,
    max_examples=8,
    n_threads=16,
    seed=42,
)

metric_config = MetricConfig(
    metric=LevenshteinRatio(project_name=project_name),
    inputs={
        "output": from_llm_response_text(),
        "reference": from_dataset_field(name="answer"),
    },
)

task_config = TaskConfig(
    instruction_prompt=prompt_instruction,
    input_dataset_fields=["question"],
    output_dataset_field="answer",
    use_chat_prompt=True,
)

result = optimizer.optimize_prompt(
    dataset=hot_pot_dataset,
    metric_config=metric_config,
    task_config=task_config,
    n_trials=10,
    n_samples=150,
)

result.display()
```

More examples can be found in the `scripts` folder.

## Installation

```bash
pip install opik-optimizer
```

## Development

To use the Opik Optimizer from source:

```bash
git clone git clone git@github.com:comet-ml/opik
cd sdks/opik_optimizer
pip install -e .
```

## Requirements

- Python 3.10+ < 3.13
- Opik API key
- OpenAI API key (or other LLM provider)
