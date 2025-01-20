---
sidebar_label: Evaluate Prompts
description: Step by step guide on how to evaluate LLM prompts
---

# Evaluate Prompts

When developing prompts and performing prompt engineering, it can be challenging to know if a new prompt is better than the previous version.

Opik Experiments allow you to evaluate the prompt on multiple samples, score each LLM output and compare the performance of different prompts.

![Experiment page](/img/evaluation/experiment_items.png)

There are two way to evaluate a prompt in Opik:

1. Using the prompt playground
2. Using the `evaluate_prompt` function in the Python SDK

## Using the prompt playground

The Opik playground allows you to quickly test different prompts and see how they perform.

You can compare multiple prompts to each other by clicking the `+ Add prompt` button in the top right corner of the playground. This will allow you to enter multiple prompts and compare them side by side.

In order to evaluate the prompts on samples, you can add variables to the prompt messages using the `{{variable}}` syntax. You can then connect a dataset and run the prompts on each dataset item.

![Playground evaluation](/img/evaluation/playground_evaluation.gif)

## Using the Python SDK

The Python SDK provides a simple way to evaluate prompts using the `evaluate_prompt` function. This methods allows you to specify a dataset, a prompt and a model. The prompt is then evaluated on each dataset item and the output can then be reviewed and annotated in the Opik UI.

To run the experiment, you can use the following code:

```python
import opik
from opik.evaluation import evaluate_prompt

# Create a dataset that contains the samples you want to evaluate
opik_client = opik.Opik()
dataset = opik_client.get_or_create_dataset("my_dataset")
dataset.insert([
    {"input": "Hello, world!", "expected_output": "Hello, world!"},
    {"input": "What is the capital of France?", "expected_output": "Paris"},
])

# Run the evaluation
evaluate_prompt(
    dataset=dataset,
    messages=[
        {"role": "user", "content": "Translate the following text to French: {{input}}"},
    ],
    model="gpt-3.5-turbo",
)
```

Once the evaluation is complete, you can view the responses in the Opik UI and score each LLM output.

![Experiment page](/img/evaluation/experiment_items.png)

### Automate the scoring process

Manually reviewing each LLM output can be time-consuming and error-prone. The `evaluate_prompt` function allows you to specify a list of scoring metrics which allows you to score each LLM output. Opik has a set of built-in metrics that allow you to detect hallucinations, answer relevance, etc and if we don't have the metric you need, you can easily create your own.

You can find a full list of all the Opik supported metrics in the [Metrics Overview](/evaluation/metrics/overview.md) section or you can define your own metric using [Custom Metrics](/evaluation/metrics/custom_metric.md).

By adding the `scoring_metrics` parameter to the `evaluate_prompt` function, you can specify a list of metrics to use for scoring. We will update the example above to use the `Hallucination` metric for scoring:

```python
import opik
from opik.evaluation import evaluate_prompt
from opik.evaluation.metrics import Hallucination

# Create a dataset that contains the samples you want to evaluate
opik_client = opik.Opik()
dataset = opik_client.get_or_create_dataset("my_dataset")
dataset.insert([
    {"input": "Hello, world!", "expected_output": "Hello, world!"},
    {"input": "What is the capital of France?", "expected_output": "Paris"},
])

# Run the evaluation
evaluate_prompt(
    dataset=dataset,
    messages=[
        {"role": "user", "content": "Translate the following text to French: {{input}}"},
    ],
    model="gpt-3.5-turbo",
    scoring_metrics=[Hallucination()],
)
```

### Customizing the model used

You can customize the model used by create a new model using the [`LiteLLMChatModel`](https://www.comet.com/docs/opik/python-sdk-reference/Objects/LiteLLMChatModel.html) class. This supports passing additional parameters to the model like the `temperature` or base url to use for the model.

```python
import opik
from opik.evaluation import evaluate_prompt
from opik.evaluation.metrics import Hallucination
from opik.evaluation import models

# Create a dataset that contains the samples you want to evaluate
opik_client = opik.Opik()
dataset = opik_client.get_or_create_dataset("my_dataset")
dataset.insert([
    {"input": "Hello, world!", "expected_output": "Hello, world!"},
    {"input": "What is the capital of France?", "expected_output": "Paris"},
])

# Run the evaluation
evaluate_prompt(
    dataset=dataset,
    messages=[
        {"role": "user", "content": "Translate the following text to French: {{input}}"},
    ],
    model=models.LiteLLMChatModel(model="gpt-3.5-turbo", temperature=0),
    scoring_metrics=[Hallucination()],
)
```

## Next steps

To evaluate comples LLM applications like RAG applications or agents, you can use the [`evaluate`](/evaluation/evaluate_your_llm.md) function.
