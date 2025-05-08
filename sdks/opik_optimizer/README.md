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
   pip install git+https://github.com/comet-ml/opik#subdirectory=sdks/opik_optimizer
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
from opik_optimizer.few_shot_bayesian_optimizer import FewShotBayesianOptimizer
from opik_optimizer.demo import get_or_create_dataset

from opik_optimizer import (
    OptimizationConfig,
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

initial_prompt_no_examples = [
    {"role": "system", "content": prompt_instruction},
    {"role": "user", "content": "{{question}}"},
]

optimizer = FewShotBayesianOptimizer(
    model="gpt-4o-mini",
    project_name="optimize-few-shot-bayesian-hotpot",
    min_examples=3,
    max_examples=8,
    n_threads=16,
    seed=42,
)

optimization_config = OptimizationConfig(
    dataset=hot_pot_dataset,
    objective=MetricConfig(
        metric=LevenshteinRatio(),
        inputs={
            "output": from_llm_response_text(),
            "reference": from_dataset_field(name="answer"),
        },
    ),
    task=TaskConfig(
        instruction_prompt=prompt_instruction,
        input_dataset_fields=["question"],
        output_dataset_field="answer",
        use_chat_prompt=True,
    ),
)

result = optimizer.optimize_prompt(optimization_config, n_trials=10)
print(result)
```

More examples can be found in the `scripts` folder.

## Installation

```bash
pip install git+https://github.com/comet-ml/opik#subdirectory=sdks/opik_optimizer
```

## Development

To use the Opik Optimizer from source:

```bash
git clone git clone git@github.com:comet-ml/opik
cd sdks/opik_optimizer
pip install -e .
```

## Requirements

- Python 3.10+
- Opik API key
- OpenAI API key (or other LLM provider)
