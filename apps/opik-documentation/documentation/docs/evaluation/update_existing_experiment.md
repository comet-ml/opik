---
sidebar_label: Update an existing experiment
---

# Update an existing experiment

Sometimes you may want to update an existing experiment with new scores, or update existing scores for an experiment. You can do this using the [`evaluate_experiment` function](https://www.comet.com/docs/opik/python-sdk-reference/evaluation/evaluate_existing.html).

This function will re-run the scoring metrics on the existing experiment items and update the scores:

```python
from opik.evaluation import evaluate_experiment
from opik.evaluation.metrics import Hallucination

hallucination_metric = Hallucination()

# Replace "my-experiment" with the name of your experiment which can be found in the Opik UI
evaluate_experiment(experiment_name="my-experiment", scoring_metrics=[hallucination_metric])
```

:::tip
The `evaluate_experiment` function can be used to update existing scores for an experiment. If you use a scoring metric with the same name as an existing score, the scores will be updated with the new values.
:::

## Example

### Create an experiment

Suppose you are building a chatbot and want to compute the hallucination scores for a set of example conversations. For this you would create a first experiment with the `evaluate` function:

```python
from opik import Opik, track
from opik.evaluation import evaluate
from opik.evaluation.metrics import Hallucination
from opik.integrations.openai import track_openai
import openai

# Define the task to evaluate
openai_client = track_openai(openai.OpenAI())

MODEL = "gpt-3.5-turbo"

@track
def your_llm_application(input: str) -> str:
    response = openai_client.chat.completions.create(
        model=MODEL,
        messages=[{"role": "user", "content": input}],
    )

    return response.choices[0].message.content

# Define the evaluation task
def evaluation_task(x):
    return {
        "input": x['user_question'],
        "output": your_llm_application(x['user_question'])
    }

# Create a simple dataset
client = Opik()
try:
    dataset = client.create_dataset(name="your-dataset-name")
    dataset.insert([
        {"input": {"user_question": "What is the capital of France?"}},
        {"input": {"user_question": "What is the capital of Germany?"}},
    ])
except:
    dataset = client.get_dataset(name="your-dataset-name")

# Define the metrics
hallucination_metric = Hallucination()


evaluation = evaluate(
    experiment_name="My experiment",
    dataset=dataset,
    task=evaluation_task,
    scoring_metrics=[hallucination_metric],
    experiment_config={
        "model": MODEL
    }
)

experiment_name = evaluation.experiment_name
print(f"Experiment name: {experiment_name}")
```

:::tip
Learn more about the `evaluate` function in our [LLM evaluation guide](/evaluation/evaluate_your_llm).
:::

### Update the experiment

Once the first experiment is created, you realise that you also want to compute a moderation score for each example. You could re-run the experiment with new scoring metrics but this means re-running the output. Instead, you can simply update the experiment with the new scoring metrics:

```python
from opik.evaluation import evaluate_experiment
from opik.evaluation.metrics import Moderation

moderation_metric = Moderation()

evaluate_experiment(experiment_name="already_existing_experiment", scoring_metrics=[moderation_metric])
```
