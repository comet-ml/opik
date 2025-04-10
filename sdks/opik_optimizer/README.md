# Opik Optimizer

The Opik Opitmizer can refine your prompts to get better performance
from your LLMs. You can use a variety of algorithms, including:

* FewShotOptimizer
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

```python
from opik_optimizer.demo import get_or_create_dataset

from opik.evaluation.metrics import LevenshteinRatio
from opik_optimizer.few_shot_optimizer import FewShotOptimizer

optimizer = FewShotOptimizer(
    model="openai/gpt-4o-mini",  # LiteLLM or OpenAI name
    project_name="optimize-few-shot-hotpot",
    temperature=0.1,
    max_tokens=5000,
)

opik_dataset = get_or_create_dataset("hotpot-300")

initial_prompt = "Answer the question"

score = optimizer.evaluate_prompt(
    dataset=opik_dataset,
    metric=LevenshteinRatio(),
    prompt=initial_prompt,
    # Algorithm-specific kwargs:
    input_key="question",
    output_key="answer",
)

print("Initial prompt:", initial_prompt)
print("Score:", score)

results = optimizer.optimize_prompt(
    dataset=opik_dataset,
    metric=LevenshteinRatio(),
    prompt=initial_prompt,
    # Algorithm-specific kwargs:
    input_key="question",
    output_key="answer",
)

print(results)
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
