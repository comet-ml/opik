---
sidebar_position: 1
sidebar_label: Overview
---

# Overview

Opik provides a set of built-in evaluation metrics that can be used to evaluate the output of your LLM calls. These metrics are broken down into two main categories:

1. Heuristic metrics
2. LLM as a Judge metrics

Heuristic metrics are deterministic and are often statistical in nature. LLM as a Judge metrics are non-deterministic and are based on the idea of using an LLM to evaluate the output of another LLM.

Opik provides the following built-in evaluation metrics:

| Metric           | Type           | Description                                                                                       | Documentation                                                         |
| ---------------- | -------------- | ------------------------------------------------------------------------------------------------- | --------------------------------------------------------------------- |
| Equals           | Heuristic      | Checks if the output exactly matches an expected string                                           | [Equals](/evaluation/metrics/heuristic_metrics#equals)                |
| Contains         | Heuristic      | Check if the output contains a specific substring, can be both case sensitive or case insensitive | [Contains](/evaluation/metrics/heuristic_metrics#contains)            |
| RegexMatch       | Heuristic      | Checks if the output matches a specified regular expression pattern                               | [RegexMatch](/evaluation/metrics/heuristic_metrics#regexmatch)        |
| IsJson           | Heuristic      | Checks if the output is a valid JSON object                                                       | [IsJson](/evaluation/metrics/heuristic_metrics#isjson)                |
| Levenshtein      | Heuristic      | Calculates the Levenshtein distance between the output and an expected string                     | [Levenshtein](/evaluation/metrics/heuristic_metrics#levenshteinratio) |
| Hallucination    | LLM as a Judge | Check if the output contains any hallucinations                                                   | [Hallucination](/evaluation/metrics/hallucination)                    |
| Moderation       | LLM as a Judge | Check if the output contains any harmful content                                                  | [Moderation](/evaluation/metrics/moderation)                          |
| AnswerRelevance  | LLM as a Judge | Check if the output is relevant to the question                                                   | [AnswerRelevance](/evaluation/metrics/answer_relevance)               |
| ContextRecall    | LLM as a Judge | Check if the output contains any hallucinations                                                   | [ContextRecall](/evaluation/metrics/context_recall)                   |
| ContextPrecision | LLM as a Judge | Check if the output contains any hallucinations                                                   | [ContextPrecision](/evaluation/metrics/context_precision)             |

You can also create your own custom metric, learn more about it in the [Custom Metric](/evaluation/metrics/custom_metric) section.
