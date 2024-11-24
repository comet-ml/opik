---
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
```

:::tip
We have added here the `track` decorator so that this traces and all it's nested steps are logged to the platform for further analysis.
:::

## 2. Define the evaluation task

Once you have added instrumentation to your LLM application, we can define the evaluation task. The evaluation task takes in as an input a dataset item and needs to return a dictionary with keys that match the parameters expected by the metrics you are using. In this example we can define the evaluation task as follows:

```python
def evaluation_task(x):
    return {
        "output": your_llm_application(x['user_question'])
    }
```

:::warning
If the dictionary returned does not match with the parameters expected by the metrics, you will get inconsistent evaluation results.
:::

## 3. Choose the evaluation Dataset

In order to create an evaluation experiment, you will need to have a Dataset that includes all your test cases.

If you have already created a Dataset, you can use the [`Opik.get_or_create_dataset`](https://www.comet.com/docs/opik/python-sdk-reference/Opik.html#opik.Opik.get_or_create_dataset) function to fetch it:

```python
from opik import Opik

client = Opik()
dataset = client.get_or_create_dataset(name="Example dataset")
```

If you don't have a Dataset yet, you can insert dataset items using the [`Dataset.insert`](https://www.comet.com/docs/opik/python-sdk-reference/evaluation/Dataset.html#opik.Dataset.insert) method. You can call this method multiple times as Opik performs data deplication before ingestion:

```python
from opik import Opik

client = Opik()
dataset = client.get_or_create_dataset(name="Example dataset")

dataset.insert([
    {"input": "Hello, world!", "expected_output": "Hello, world!"},
    {"input": "What is the capital of France?", "expected_output": "Paris"},
])
```

## 4. Choose evaluation metrics

Opik provides a set of built-in evaluation metrics that you can choose from. These are broken down into two main categories:

1. Heuristic metrics: These metrics that are deterministic in nature, for example `equals` or `contains`
2. LLM as a judge: These metrics use an LLM to judge the quality of the output, typically these are used for detecting `hallucinations` or `context relevance`

In the same evaluation experiment, you can use multiple metrics to evaluate your application:

```python
from opik.evaluation.metrics import Hallucination

hallucination_metric = Hallucination()
```

:::tip
Each metric expects the data in a certain format, you will need to ensure that the task you have defined in step 1. returns the data in the correct format.
:::

## 5. Run the evaluation

Now that we have the task we want to evaluate, the dataset to evaluate on, the metrics we want to evalation with, we can run the evaluation:

```python
from opik import Opik, track
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
def evaluation_task(x):
    return {
        "output": your_llm_application(x['user_question'])
    }

# Create a simple dataset
client = Opik()
dataset = client.get_or_create_dataset(name="Example dataset")
dataset.insert([
    {"input": "What is the capital of France?"},
    {"input": "What is the capital of Germany?"},
])

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

### Missing arguments for scoring methods

When you face the `opik.exceptions.ScoreMethodMissingArguments` exception, it means that the dataset item and task output dictionaries do not contain all the arguments expected by the scoring method. The way the evaluate function works is by merging the dataset item and task output dictionaries and then passing the result to the scoring method. For example, if the dataset item contains the keys `user_question` and `context` while the evaluation task returns a dictionary with the key `output`, the scoring method will be called as `scoring_method.score(user_question='...', context= '...', output= '...')`. This can be an issue if the scoring method expects a different set of arguments.

You can solve this by either updating the dataset item or evaluation task to return the missing arguments or by using the `scoring_key_mapping` parameter of the `evaluate` function. In the example above, if the scoring method expects `input` as an argument, you can map the `user_question` key to the `input` key as follows:

```python
evaluation = evaluate(
    dataset=dataset,
    task=evaluation_task,
    scoring_metrics=[hallucination_metric],
    scoring_key_mapping={"input": "user_question"},
)
```

### Linking prompts to experiments

The [Opik prompt library](/library/prompt_management.mdx) can be used to version your prompt templates.

When creating an Experiment, you can link the Experiment to a specific prompt version:

```python
import opik

# Create a prompt
prompt = opik.Prompt(
    name="My prompt",
    prompt="..."
)

# Run the evaluation
evaluation = evaluate(
    experiment_name="My experiment",
    dataset=dataset,
    task=evaluation_task,
    scoring_metrics=[hallucination_metric],
    prompt=prompt,
)
```

The experiment will now be linked to the prompt allowing you to view all experiments that use a specific prompt:
![linked prompt](/img/evaluation/linked_prompt.png)

### Logging traces to a specific project

You can use the `project_name` parameter of the `evaluate` function to log evaluation traces to a specific project:

```python
evaluation = evaluate(
    dataset=dataset,
    task=evaluation_task,
    scoring_metrics=[hallucination_metric],
    project_name="hallucination-detection",
)
```

### Evaluating a subset of the dataset

You can use the `nb_samples` parameter to specify the number of samples to use for the evaluation. This is useful if you only want to evaluate a subset of the dataset.

```python
evaluation = evaluate(
    experiment_name="My experiment",
    dataset=dataset,
    task=evaluation_task,
    scoring_metrics=[hallucination_metric],
    nb_samples=10,
)
```

### Disabling threading

In order to evaluate datasets more efficiently, Opik uses multiple background threads to evaluate the dataset. If this is causing issues, you can disable these by setting `task_threads` and `scoring_threads` to `1` which will lead Opik to run all calculations in the main thread.
