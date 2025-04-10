# Opik Optimizer

The Opik Opitmizer can refine your prompts to get better performance
from your LLMs. You can use a variety of algorithms, including:

* FewShotBayesianOptimizer
* FewShotOptimizer
* MiproOptimizer
* MetaPromptOptimizer

## Setup

1. Configure Comet ML:
   ```bash
   # Install Comet ML CLI
   pip install opik
   
   # Configure your API key
   opik configure
   # When prompted, enter your Comet ML API key
   ```

2. Set up your environment variables:
   ```bash
   # OpenAI API key for LLM access
   export OPENAI_API_KEY=your_openai_api_key
   
   # Comet ML API key for logging
   export COMET_API_KEY=your_comet_api_key
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
from opik_optimizer import MiproOptimizer
from opik.evaluation.metrics import LevenshteinRatio
import os

optimizer = MiproOptimizer(
    model="openai/gpt-4o-mini",  # LiteLLM name
    api_key=os.environ["OPENAI_API_KEY"],
    temperature=0.1,
    max_tokens=5000,
)

best_prompt = optimizer.optimize_prompt(
    dataset="hotpot-300",
    metric=LevenshteinRatio(),
    prompt="Answer the question with a short, 1 to 5 word phrase",
    # kwargs:
    input="question",
    output="answer",
)

print(best_prompt)
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
- Comet ML account and API key
- OpenAI API key (or other LLM provider)