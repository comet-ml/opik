# Evaluating Opik's Hallucination Metric

For this guide we will be evaluating the Hallucination metric included in the LLM Evaluation SDK which will showcase both how to use the `evaluation` functionality in the platform as well as the quality of the Hallucination metric included in the SDK.

## Creating an account on Comet.com

[Comet](https://www.comet.com/site/?from=llm&utm_source=opik&utm_medium=colab&utm_content=eval_hall&utm_campaign=opik) provides a hosted version of the Opik platform, [simply create an account](https://www.comet.com/signup?from=llm&utm_source=opik&utm_medium=colab&utm_content=eval_hall&utm_campaign=opik) and grab you API Key.

> You can also run the Opik platform locally, see the [installation guide](https://www.comet.com/docs/opik/self-host/overview/?from=llm&utm_source=opik&utm_medium=colab&utm_content=eval_hall&utm_campaign=opik) for more information.


```python
%pip install opik pyarrow pandas fsspec huggingface_hub --upgrade --quiet
```


```python
import opik

opik.configure(use_local=False)
```

## Preparing our environment

First, we will install configure the OpenAI API key and create a new Opik dataset


```python
import os
import getpass

if "OPENAI_API_KEY" not in os.environ:
    os.environ["OPENAI_API_KEY"] = getpass.getpass("Enter your OpenAI API key: ")
```

We will be using the [HaluBench dataset](https://huggingface.co/datasets/PatronusAI/HaluBench?library=pandas) which according to this [paper](https://arxiv.org/pdf/2407.08488) GPT-4o detects 87.9% of hallucinations. The first step will be to create a dataset in the platform so we can keep track of the results of the evaluation.

Since the insert methods in the SDK deduplicates items, we can insert 50 items and if the items already exist, Opik will automatically remove them.


```python
# Create dataset
import opik
import pandas as pd

client = opik.Opik()

# Create dataset
dataset = client.get_or_create_dataset(
    name="HaluBench", description="HaluBench dataset"
)

# Insert items into dataset
df = pd.read_parquet(
    "hf://datasets/PatronusAI/HaluBench/data/test-00000-of-00001.parquet"
)
df = df.sample(n=50, random_state=42)

dataset_records = [
    {
        "input": x["question"],
        "context": [x["passage"]],
        "llm_output": x["answer"],
        "expected_hallucination_label": x["label"],
    }
    for x in df.to_dict(orient="records")
]

dataset.insert(dataset_records)
```

## Evaluating the hallucination metric

In order to evaluate the performance of the Opik hallucination metric, we will define:

- Evaluation task: Our evaluation task will use the data in the Dataset to return a hallucination score computed using the Opik hallucination metric.
- Scoring metric: We will use the `Equals` metric to check if the hallucination score computed matches the expected output.

By defining the evaluation task in this way, we will be able to understand how well Opik's hallucination metric is able to detect hallucinations in the dataset.


```python
from opik.evaluation.metrics import Hallucination, Equals
from opik.evaluation import evaluate
from opik import Opik
from opik.evaluation.metrics.llm_judges.hallucination.template import generate_query
from typing import Dict


# Define the evaluation task
def evaluation_task(x: Dict):
    metric = Hallucination()
    try:
        metric_score = metric.score(
            input=x["input"], context=x["context"], output=x["llm_output"]
        )
        hallucination_score = metric_score.value
        hallucination_reason = metric_score.reason
    except Exception as e:
        print(e)
        hallucination_score = None
        hallucination_reason = str(e)

    return {
        "hallucination_score": "FAIL" if hallucination_score == 1 else "PASS",
        "hallucination_reason": hallucination_reason
    }


# Get the dataset
client = Opik()
dataset = client.get_dataset(name="HaluBench")

# Define the scoring metric
check_hallucinated_metric = Equals(name="Correct hallucination score")

# Add the prompt template as an experiment configuration
experiment_config = {
    "prompt_template": generate_query(
        input="{input}", context="{context}", output="{output}", few_shot_examples=[]
    )
}

res = evaluate(
    dataset=dataset,
    task=evaluation_task,
    scoring_metrics=[check_hallucinated_metric],
    experiment_config=experiment_config,
    scoring_key_mapping={"reference": "expected_hallucination_label", "output": "hallucination_score"},
)
```

We can see that the hallucination metric is able to detect ~80% of the hallucinations contained in the dataset and we can see the specific items where hallucinations were not detected.

![Hallucination Evaluation](https://raw.githubusercontent.com/comet-ml/opik/main/apps/opik-documentation/documentation/static/img/cookbook/hallucination_metric_cookbook.png)
