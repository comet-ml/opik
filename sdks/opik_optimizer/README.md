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
   pip install comet_ml
   
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

## Example

```python
from opik_optimizer import FewShotBayesianOptimizer
from opik.evaluation.metrics import LevenshteinRatio
import os
from datasets import load_dataset
import opik
from opik.api_objects.opik_client import Opik

# Initialize opik client
client = Opik()

try:
    # Try to get existing dataset
    opik_dataset = opik.get_dataset("hotpot-qa-optimizer")
except Exception:
    # If dataset doesn't exist, create it
    opik_dataset = opik.Dataset(
        name="hotpot-qa-optimizer",
        description="HotpotQA dataset for prompt optimization",
        rest_client=client._rest_client
    )
    
    # Load and insert data from Hugging Face
    hf_dataset = load_dataset("BeIR/hotpotqa", "corpus")
    
    # Convert and insert data
    for item in hf_dataset:
        opik_dataset.insert(
            question=item["question"],
            answer=item["answer"],
            context=item["context"]
        )

optimizer = FewShotBayesianOptimizer(
    model="openai/gpt-4o-mini",  # LiteLLM name
    api_key=os.environ["OPENAI_API_KEY"],
    temperature=0.1,
    max_tokens=5000,
)

best_prompt = optimizer.optimize_prompt(
    dataset=opik_dataset,  # Use the opik dataset
    metric=LevenshteinRatio(),
    prompt="Answer the question with a short, 1 to 5 word phrase",
    # kwargs:
    input="question",
    output="answer",
)

print(best_prompt)
```

More examples can be found in the `scripts` folder.

## Development

To use the Opik Optimizer from source:

```bash
git clone git clone git@github.com:comet-ml/opik
cd sdks/opik_optimizer
pip install -e .
```

## Available Datasets

The optimizer supports various datasets, including:

- HotpotQA (via Hugging Face: `BeIR/hotpotqa`)
- Custom datasets through the opik framework
- Other BEIR benchmark datasets

To use datasets efficiently:

```python
import opik
from opik.api_objects.opik_client import Opik

# Initialize opik client
client = Opik()

try:
    # Try to get existing dataset
    dataset = opik.get_dataset("your-dataset-name")
except Exception:
    # If dataset doesn't exist, create it
    dataset = opik.Dataset(
        name="your-dataset-name",
        description="Your dataset description",
        rest_client=client._rest_client
    )
    
    # Load and insert your data
    # For Hugging Face datasets:
    hf_dataset = load_dataset("dataset-name")
    for item in hf_dataset:
        dataset.insert(
            # Map your fields here
            field1=item["hf_field1"],
            field2=item["hf_field2"]
        )
```

This approach:
1. Avoids re-downloading data on every run
2. Caches the dataset locally
3. Only creates the dataset if it doesn't exist
4. Uses the correct opik client initialization

## Requirements

- Python 3.10+
- Comet ML account and API key
- OpenAI API key (or other LLM provider)
- Hugging Face datasets (for loading benchmark datasets)
