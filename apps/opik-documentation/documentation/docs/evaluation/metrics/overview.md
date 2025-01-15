---
sidebar_label: Overview
description: Describes all the built-in evaluation metrics provided by Opik
---

# Overview

Opik provides a set of built-in evaluation metrics that can be used to evaluate the output of your LLM calls. These metrics are broken down into two main categories:

1. Heuristic metrics
2. LLM as a Judge metrics

Heuristic metrics are deterministic and are often statistical in nature. LLM as a Judge metrics are non-deterministic and are based on the idea of using an LLM to evaluate the output of another LLM.

Opik provides the following built-in evaluation metrics:

| Metric           | Type           | Description                                                                                       | Documentation                                                            |
| ---------------- | -------------- | ------------------------------------------------------------------------------------------------- | ------------------------------------------------------------------------ |
| Equals           | Heuristic      | Checks if the output exactly matches an expected string                                           | [Equals](/evaluation/metrics/heuristic_metrics.md#equals)                |
| Contains         | Heuristic      | Check if the output contains a specific substring, can be both case sensitive or case insensitive | [Contains](/evaluation/metrics/heuristic_metrics.md#contains)            |
| RegexMatch       | Heuristic      | Checks if the output matches a specified regular expression pattern                               | [RegexMatch](/evaluation/metrics/heuristic_metrics.md#regexmatch)        |
| IsJson           | Heuristic      | Checks if the output is a valid JSON object                                                       | [IsJson](/evaluation/metrics/heuristic_metrics.md#isjson)                |
| Levenshtein      | Heuristic      | Calculates the Levenshtein distance between the output and an expected string                     | [Levenshtein](/evaluation/metrics/heuristic_metrics.md#levenshteinratio) |
| Hallucination    | LLM as a Judge | Check if the output contains any hallucinations                                                   | [Hallucination](/evaluation/metrics/hallucination.md)                    |
| G-Eval           | LLM as a Judge | Task agnostic LLM as a Judge metric                                                               | [G-Eval](/evaluation/metrics/g_eval.md)                                  |
| Moderation       | LLM as a Judge | Check if the output contains any harmful content                                                  | [Moderation](/evaluation/metrics/moderation.md)                          |
| AnswerRelevance  | LLM as a Judge | Check if the output is relevant to the question                                                   | [AnswerRelevance](/evaluation/metrics/answer_relevance.md)               |
| ContextRecall    | LLM as a Judge | Check if the output contains any hallucinations                                                   | [ContextRecall](/evaluation/metrics/context_recall.md)                   |
| ContextPrecision | LLM as a Judge | Check if the output contains any hallucinations                                                   | [ContextPrecision](/evaluation/metrics/context_precision.md)             |

You can also create your own custom metric, learn more about it in the [Custom Metric](/evaluation/metrics/custom_metric.md) section.

## Customizing LLM as a Judge metrics

By default, Opik uses GPT-4o from OpenAI as the LLM to evaluate the output of other LLMs. However, you can easily switch to another LLM provider by specifying a different `model` in the `model_name` parameter of each LLM as a Judge metric.

```python pytest_codeblocks_skip=true
from opik.evaluation.metrics import Hallucination

metric = Hallucination(model="bedrock/anthropic.claude-3-sonnet-20240229-v1:0")

metric.score(
    input="What is the capital of France?",
    output="The capital of France is Paris. It is famous for its iconic Eiffel Tower and rich cultural heritage.",
)
```

This functionality is based on LiteLLM framework, you can find a full list of supported LLM providers and how to configure them in the [LiteLLM Providers](https://docs.litellm.ai/docs/providers) guide.
