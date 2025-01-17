---
sidebar_label: Online evaluation
description: Describes how to define scoring rules for production traces
---

# Online evaluation

:::tip
Online evaluation metrics allow you to score all your production traces and easily identify any issues with your production LLM application.
:::

When working with LLMs in production, the sheer number of traces means that it isn't possible to manually review each trace. Opik allows you to define LLM as a Judge metrics that will automatically score the LLM calls logged to the platform.

![Opik LLM as a Judge](/img/production/online_evaluation.gif)

By defining LLM as a Judge metrics that run on all your production traces, you will be able to
automate the monitoring of your LLM calls for hallucinations, answer relevance or any other task
specific metric.

## Defining scoring rules

Scoring rules can be defined through both the UI and the [REST API](/reference/rest_api/create-automation-rule-evaluator.api.mdx).

To create a new scoring metric in the UI, first navigate to the project you would like to monitor. Once you have navigated to the `rules` tab, you will be able to create a new rule.

![Online evaluation rule modal](/img/production/online_evaluation_rule_modal.png)

When creating a new rule, you will be presented with the following options:

1. **Name:** The name of the rule
2. **Sampling rate:** The percentage of traces to score. When set to `1`, all traces will be scored.
3. **Model:** The model to use to run the LLM as a Judge metric. As we use structured outputs to ensure the consistency of the LLM response, you will only be able to use `gpt-4o` and `gpt-4o-mini` models.
4. **Prompt:** The LLM as a Judge prompt to use. Opik provides a set of base prompts (Hallucination, Moderation, Answer Relevance) that you can use or you can define your own. Variables in the prompt should be in `{{variable_name}}` format.
5. **Variable mapping:** This is the mapping of the variables in the prompt to the values from the trace.
6. **Score definition:** This is the format of the output of the LLM as a Judge metric. By adding more than one score, you can define LLM as a Judge metrics that score an LLM output along different dimensions.

### Opik's built-in LLM as a Judge metrics

Opik comes pre-configured with 3 different LLM as a Judge metrics:

1. Hallucination: This metric checks if the LLM output contains any hallucinated information.
2. Moderation: This metric checks if the LLM output contains any offensive content.
3. Answer Relevance: This metric checks if the LLM output is relevant to the given context.

:::tip
If you would like us to add more LLM as a Judge metrics to the platform, do raise an issue on [GitHub](https://github.com/comet-ml/opik/issues) and we will do our best to add them !
:::

### Writing your own LLM as a Judge metric

Opik's built-in LLM as a Judge metrics are very easy to use and are great for getting started. However, as you start working on more complex tasks, you may need to write your own LLM as a Judge metrics.

We typically recommend that you experiment with LLM as a Judge metrics during development using [Opik's evaluation framework](/evaluation/overview.mdx). Once you have a metric that works well for your use case, you can then use it in production.

![Custom LLM as a Judge](/img/production/online_evaluation_custom_judge.png)
When writing your own LLM as a Judge metric you will need to specify the prompt variables using the mustache syntax, ie. `{{variable_name}}`. You can then map these variables to your trace data using the `variable_mapping` parameter. When the rule is executed, Opik will replace the variables with the values from the trace data.

You can control the format of the output using the `Scoring definition` parameter. This is were you can define the scores you want the LLM as a Judge metric to return. Under the hood, we will use this definition in conjunction with the [structured outputs](https://platform.openai.com/docs/guides/structured-outputs) functionality to ensure that the the LLM as a Judge metric always returns trace scores.

## Reviewing online evaluation scores

The scores returned by the online evaluation rules will be stored as feedback scores for each trace. This will allow you to review these scores in the traces sidebar and track their changes over time in the Opik dashboard.

![Opik dashboard](/img/production/opik_monitoring_dashboard.gif)

You can also view the average feedback scores for all the traces in your project from the traces table.
