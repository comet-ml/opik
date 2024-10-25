# Evaluating Opik's Moderation Metric

For this guide we will be evaluating the Moderation metric included in the LLM Evaluation SDK which will showcase both how to use the `evaluation` functionality in the platform as well as the quality of the Moderation metric included in the SDK.

## Creating an account on Comet.com

[Comet](https://www.comet.com/site?from=llm&utm_source=opik&utm_medium=colab&utm_content=eval_mod&utm_campaign=opik) provides a hosted version of the Opik platform, [simply create an account](https://www.comet.com/signup/?from=llm&utm_source=opik&utm_medium=colab&utm_content=eval_mod&utm_campaign=opik) and grab you API Key.

> You can also run the Opik platform locally, see the [installation guide](https://www.comet.com/docs/opik/self-host/overview/?from=llm&utm_source=opik&utm_medium=colab&utm_content=eval_mod&utm_campaign=opik) for more information.


```python
%pip install --upgrade --quiet opik
```


```python
import opik

opik.configure(use_local=False)
```

## Preparing our environment

First, we will configure the OpenAI API key and download a reference moderation dataset.


```python
import os
import getpass

if "OPENAI_API_KEY" not in os.environ:
    os.environ["OPENAI_API_KEY"] = getpass.getpass("Enter your OpenAI API key: ")
```

We will be using the [OpenAI Moderation API Release dataset](https://github.com/openai/moderation-api-release/tree/main/data) which according to this [blog post](https://openai.com/index/using-gpt-4-for-content-moderation/) GPT-4o detects ~60~% of hallucinations. The first step will be to create a dataset in the platform so we can keep track of the results of the evaluation.

Since the insert methods in the SDK deduplicates items, we can insert 50 items and if the items already exist, Opik will automatically remove them.


```python
# Create dataset
import opik
import pandas as pd
import requests
from io import BytesIO

client = opik.Opik()

# Create dataset
dataset = client.get_or_create_dataset(
    name="OpenAIModerationDataset", description="OpenAI Moderation Dataset"
)

# Insert items into dataset
url = "https://github.com/openai/moderation-api-release/raw/main/data/samples-1680.jsonl.gz"
response = requests.get(url)
df = pd.read_json(BytesIO(response.content), lines=True, compression="gzip")

df = df.sample(n=50, random_state=42)

dataset_records = []
for x in df.to_dict(orient="records"):
    moderation_fields = ["S", "H", "V", "HR", "SH", "S3", "H2", "V2"]
    moderated_fields = [field for field in moderation_fields if x[field] == 1.0]
    expected_output = "moderated" if moderated_fields else "not_moderated"

    dataset_records.append(
        {
            "input": x["prompt"],
            "expected_output": expected_output,
            "moderated_fields": moderated_fields,
        }
    )

dataset.insert(dataset_records)
```

## Evaluating the moderation metric

In order to evaluate the performance of the Opik moderation metric, we will define:

- Evaluation task: Our evaluation task will use the data in the Dataset to return a moderation score computed using the Opik moderation metric.
- Scoring metric: We will use the `Equals` metric to check if the moderation score computed matches the expected output.

By defining the evaluation task in this way, we will be able to understand how well Opik's moderation metric is able to detect moderation violations in the dataset.

We can use the Opik SDK to compute a moderation score for each item in the dataset:


```python
from opik.evaluation.metrics import Moderation, Equals
from opik.evaluation import evaluate
from opik import Opik
from opik.evaluation.metrics.llm_judges.moderation.template import generate_query
from typing import Dict

# Define the evaluation task
def evaluation_task(x: Dict):
    metric = Moderation()
    try:
        metric_score = metric.score(input=x["input"])
        moderation_score = metric_score.value
        moderation_reason = metric_score.reason
    except Exception as e:
        print(e)
        moderation_score = None
        moderation_reason = str(e)

    moderation_score = "moderated" if metric_score.value > 0.5 else "not_moderated"

    return {
        "output": moderation_score,
        "moderation_score": moderation_score,
        "moderation_reason": moderation_reason,
        "reference": x["expected_output"],
    }


# Get the dataset
client = Opik()
dataset = client.get_dataset(name="OpenAIModerationDataset")

# Define the scoring metric
moderation_metric = Equals(name="Correct moderation score")

# Add the prompt template as an experiment configuration
experiment_config = {
    "prompt_template": generate_query(input="{input}", few_shot_examples=[])
}

res = evaluate(
    dataset=dataset,
    task=evaluation_task,
    scoring_metrics=[moderation_metric],
    experiment_config=experiment_config,
)
```

We are able to detect ~85% of moderation violations, this can be improved further by providing some additional examples to the model. We can view a breakdown of the results in the Opik UI:

![Moderation Evaluation](https://raw.githubusercontent.com/comet-ml/opik/main/apps/opik-documentation/documentation/static/img/cookbook/moderation_metric_cookbook.png)


