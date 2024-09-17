---
sidebar_position: 3
sidebar_label: Evaluate your LLM Application
---

# Evaluate your LLM Application

Evaluating your LLM application allows you to have confidence in the performance of your LLM application. This evaluation set is often performed both during the development and as part of the testing of an application.

The evaluation is done in five steps:

1. Add tracing to your LLM application
2. Define the evaluation task
3. Choose the `Dataset` that you would like to evaluate your application on
4. Choose the metrics that you would like to evaluate your application with
5. Create and run the evaluation experiment.

## 1. Add tracking to your LLM application

While not required, we recommend adding tracking to your LLM application. This allows you to have full visibility into each evaluation run. In the example below we will use a combination of the `track` decorator and the `track_openai` function to trace the LLM application.

```python
from opik import track
from opik.integrations.openai import track_openai
import openai

openai_client = track_openai(openai.OpenAI())

# This method is the LLM application that you want to evaluate
# Typically this is not updated when creating evaluations
@track
def your_llm_application(input: str) -> str:
    response = openai_client.chat.completions.create(
        model="gpt-3.5-turbo",
        messages=[{"role": "user", "content": input}],
    )

    return response.choices[0].message.content

@track
def your_context_retriever(input: str) -> str:
    return ["..."]
```

:::tip
We have added here the `track` decorator so that this traces and all it's nested steps are logged to the platform for further analysis.
:::

## 2. Define the evaluation task

Once you have added instrumentation to your LLM application, we can define the evaluation task. The evaluation task takes in as an input a dataset item and needs to return a dictionary with keys that match the parameters expected by the metrics you are using. In this example we can define the evaluation task as follows:

```python
def evaluation_task(x: DatasetItem):
    return {
        "input": x.input['user_question'],
        "output": your_llm_application(x.input['user_question']),
        "context": your_context_retriever(x.input['user_question'])
    }
```

:::warning
If the dictionary returned does not match with the parameters expected by the metrics, you will get inconsistent evaluation results.
:::

## 3. Choose the evaluation Dataset

In order to create an evaluation experiment, you will need to have a Dataset that includes all your test cases.

If you have already created a Dataset, you can use the `comet.get_dataset` function to fetch it:

```python
from opik import Opik

client = Opik()
dataset = client.get_dataset(name="your-dataset-name")
```

If you don't have a Dataset yet, you can create one using the `Comet.create_dataset` function:

```python
from opik import Opik
from opik.datasets import DatasetItem

client = Opik()
dataset = client.create_dataset(name="your-dataset-name")

dataset.insert([
    DatasetItem(input="Hello, world!", expected_output="Hello, world!"),
    DatasetItem(input="What is the capital of France?", expected_output="Paris"),
])
```

## 4. Choose evaluation metrics

Comet provides a set of built-in evaluation metrics that you can choose from. These are broken down into two main categories:

1. Heuristic metrics: These metrics that are deterministic in nature, for example `equals` or `contains`
2. LLM as a judge: These metrics use an LLM to judge the quality of the output, typically these are used for detecting `hallucinations` or `context relevance`

In the same evaluation experiment, you can use multiple metrics to evaluate your application:

```python
from opik.evaluation.metrics import Equals, Hallucination

equals_metric = Equals()
hallucination_metric = Hallucination()
```

:::tip
Each metric expects the data in a certain format, you will need to ensure that the task you have defined in step 1. returns the data in the correct format.
:::

## 5. Run the evaluation

Now that we have the task we want to evaluate, the dataset to evaluate on, the metrics we want to evalation with, we can run the evaluation:

```python
from opik import Opik, track, DatasetItem
from opik.evaluation import evaluate
from opik.evaluation.metrics import Equals, Hallucination
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
def evaluation_task(x: DatasetItem):
    return {
        "input": x.input['user_question'],
        "output": your_llm_application(x.input['user_question']),
        "context": your_context_retriever(x.input['user_question'])
    }

@track
def your_context_retriever(input: str) -> str:
    return ["..."]


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
```

:::tip
You can use the `experiment_config` parameter to store information about your evaluation task. Typically we see teams store information about the prompt template, the model used and model parameters used to evaluate the application.
:::

## Advanced usage

In order to evaluate datasets more efficiently, Opik uses multiple background threads to evaluate the dataset. If this is causing issues, you can disable these by setting `task_threads` and `scoring_threads` to `1` which will lead Opik to run all calculations in the main thread.
