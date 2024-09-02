---
sidebar_position: 3
sidebar_label: Evaluate your LLM Application
---

# Evaluate your LLM Application

Evaluating your LLM application allows you to have confidence in the performance of your LLM application. This evaluation set is often performed both during the development and as part of the testing of an application.

The evaluation is done in three steps:

1. Define the evaluation task
2. Choose the `Dataset` that you would like to evaluate your application on
3. Choose the metrics that you would like to evaluate your application with
4. Create and run the evaluation experiment.

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

## 2. Choose the evaluation Dataset

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

## 3. Choose evaluation metrics

Comet provides a set of built-in evaluation metrics that you can choose from. These are broken down into two main categories:

1. Heuristic metrics: These metrics that are deterministic in nature, for example `equals` or `contains`
2. LLM as a judge: These metrics use an LLM to judge the quality of the output, typically these are used for detecting `hallucinations` or `context relevance`

In the same evaluation experiment, you can use multiple metrics to evaluate your application:

```python
from opik.evaluation.metrics import Equals, Hallucination

equals_metric = Equals()
contains_metric = Hallucination()
```

:::tip
Each metric expects the data in a certain format, you will need to ensure that the task you have defined in step 1. returns the data in the correct format.
:::

## 4. Run the evaluation

In order to 
Now that we have the task we want to evaluate, the dataset to evaluate on, the metrics we want to evalation with, we can run the evaluation:

```python
from opik import Opik, track, DatasetItem
from opik.evaluation import evaluate
from opik.evaluation.metrics import Equals, Hallucination
from opik.integrations.openai import track_openai

# Define the task to evaluate
openai_client = track_openai(openai.OpenAI())


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


# Fetch the dataset
client = Opik()
dataset = client.get_dataset(name="your-dataset-name")

# Define the metrics
equals_metric = Equals(search_key="expected_output")
hallucination_metric = Hallucination()


# Define and run the evaluation
def evaluation_task(x: DatasetItem):
    return {
        "input": x.input['user_question'],
        "output": your_llm_application(x.input['user_question']),
        "context": your_context_retriever(x.input['user_question'])
    }


evaluation = evaluate(
    experiment_name="My experiment",
    dataset=dataset,
    task=evaluation_task,
    scoring_metrics=[contains_metric, hallucination_metric],
)
```

:::tip
We will track the traces for all evaluations and will be logged to the `evaluation` project by default. To log it to a specific project, you can pass the `project_name` parameter to the `evaluate` function.
:::

## Advanced usage

In order to evaluate datasets more efficiently, Opik uses multiple background threads to evaluate the dataset. If this is causing issues, you can disable these by setting `task_threads` and `scoring_threads` to `1` which will lead Opik to run all calculations in the main thread.
