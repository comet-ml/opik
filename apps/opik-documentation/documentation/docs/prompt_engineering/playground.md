---
sidebar_label: Prompt playground
description: Describes Opik's prompt playground that can be used to quickly try out different prompts
---

# Prompt Playground

:::tip
The Opik prompt playground is current in public preview, if you have any feedback or suggestions, please [let us know](https://github.com/comet-ml/opik/pulls).
:::

When working with LLMs, there are time when you want to quickly try out different prompts and see how they perform. Opik's prompt playground is a great way to do just that.

![playground](/img/evaluation/playground.png)

## Configuring the prompt playground

In order to use the prompt playground, you will need to first configure the LLM provider you want to use. You can do this by clicking on the `Configuration` tab in the sidebar and navigating to the `AI providers` tab. From there, you can select the provider you want to use and enter your API key.

:::tip
Currently only OpenAI is supported but we are working on adding support for other LLM providers.
:::

## Using the prompt playground

The prompt playground is a simple interface that allows you to enter prompts and see the output of the LLM. It allows you to enter system, user and assistant messages and see the output of the LLM in real time.

You can also easily evaluate how different models impact the prompt by duplicating a prompt and changing either the model or the model parameters.

All of the conversations from the playground are logged to the `playground` project so that you can easily refer back to them later:

![playground conversations](/img/evaluation/playground_conversations.png)

## Running experiments in the playground

You can evaluate prompts in the playground by using variables in the prompts using the `{{variable}}` syntax. You can then connect a dataset and run the prompts on each dataset item. This allows both technical and non-technical users to evaluate prompts quickly and easily.

![playground evaluation](/img/evaluation/playground_evaluation.gif)

When using datasets in the playground, you need to ensure the prompt contains variables in the mustache syntax (`{{variable}}`) that align with the columns in the dataset. For example if the dataset contains a column named `user_question` you need to ensure the prompt contains `{{user_question}}`.
