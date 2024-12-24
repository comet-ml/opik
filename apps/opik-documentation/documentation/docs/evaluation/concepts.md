---
sidebar_label: Concepts
---

# Evaluation Concepts

:::tip
If you want to jump straight to running evaluations, you can head to the [Evaluate your LLM application](/docs/evaluation/evaluate_your_llm.md) section.
:::

When working with LLM applications, the bottleneck to iterating faster is often the evaluation process. While it is possible to manually review your LLM application's output, this process is slow and not scalable. Instead of manually reviewing your LLM application's output, Opik allows you to automate the evaluation of your LLM application.

In order to understand how to run evaluations in Opik, it is important to first become familiar with the concepts of:

1. **Dataset**: A dataset is a collection of samples that your LLM application will be evaluated on. Datasets only store the input and expected outputs for each sample, the output from your LLM application will be computed and scored during the evaluation process.
2. **Experiment**: An experiment is a single evaluation of your LLM application. During an experiment, we process each dataset item, compute the output based on your LLM application and then score the output.

![Evaluation Concepts](/img/evaluation/evaluation_concepts.png)

In this section, we will walk through all the concepts associated with Opik's evaluation framework.

## Datasets

The first step in automating the evaluation of your LLM application is to create a dataset which is a collection of samples that your LLM application will be evaluated on. Each dataset is made up of Dataset Items which store the input, expected output and other metadata for a single sample.

Given the importance of datasets in the evaluation process, teams often spend a significant amount of time curating and preparing their datasets. There are three main ways to create a dataset:

1. **Manually curating examples**: As a first step, you can manually curate a set of examples based on your knowledge of the application you are building. You can also leverage subject matter experts to help in the creation of the dataset.

2. **Using synthetic data**: If you don't have enough data to create a diverse set of examples, you can turn to synthetic data generation tools to help you create a dataset. The [LangChain cookbook](/docs/cookbook/langchain.md) has a great example of how to use synthetic data generation tools to create a dataset.

3. **Leveraging production data**: If you application is in production, you can leverage the data that is being generated to augment your dataset. While this is often not the first step in creating a dataset, it can be a great way to to enrich your dataset with real world data.

   If you are using Opik for production monitoring, you can easily add traces to your dataset by selecting them in the UI and selecting `Add to dataset` in the `Actions` dropdown.

:::tip
You can learn more about how to manage your datasets in Opik in the [Manage Datasets](/docs/evaluation/manage_datasets.md) section.
:::

## Experiments

Experiments are the core building block of the Opik evaluation framework. Each time you run a new evaluation, a new experiment is created. Each experiment is made up of two main components:

1. **Experiment Configuration**: The configuration object associated with each experiment allows you to track some metadata, often you would use this field to store the prompt template used for a given experiment for example.
2. **Experiment Items**: Experiment items store the input, expected output, actual output and feedback scores for each dataset sample that was processed during an experiment.

In addition, for each experiment you will be able to see the average scores for each metric.

### Experiment Configuration

One of the main advantages of having an automated evaluation framework is the ability to iterate quickly. The main drawback is that it can become difficult to track what has changed between two different iterations of an experiment.

The experiment configuration object allows you to store some metadata associated with a given experiment. This is useful for tracking things like the prompt template used for a given experiment, the model used, the temperature, etc.

You can then compare the configuration of two different experiments from the Opik UI to see what has changed.

![Experiment Configuration](/img/evaluation/compare_experiment_config.png)

### Experiment Items

Experiment items store the input, expected output, actual output and feedback scores for each dataset sample that was processed during an experiment. In addition, a trace is associated with each item to allow you to easily understand why a given item scored the way it did.

![Experiment Items](/img/evaluation/experiment_items.png)

## Running an evaluation

When you run an evaluation, you will need to know the following:

1. Dataset: The dataset you want to run the evaluation on.
2. Evaluation task: This maps the inputs stored in the dataset to the output you would like to score. The evaluation task is typically the LLM application you are building.
3. Metrics: The metrics you would like to use when scoring the outputs of your LLM

You can then run the evaluation using the `evaluate` function:

```python
from opik import evaluate

evaluate(
    dataset=dataset,
    evaluation_task=evaluation_task,
    metrics=metrics,
    experiment_config={"prompt_template": "..."},
)
```

:::tip
You can find a full tutorial on defining evaluations in the [Evaluate your LLM application](/docs/evaluation/evaluate_your_llm.md) section.
:::
