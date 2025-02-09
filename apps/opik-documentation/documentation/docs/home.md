---
slug: /
sidebar_label: Home
description: Opik documentation home page
---

# Opik by Comet

The Opik platform allows you to log, view and evaluate your LLM traces during both development and production. Using the platform and our LLM as a Judge evaluators, you can identify and fix issues in your LLM application.

![LLM Evaluation Platform](/img/home/traces_page_with_sidebar.png)

:::tip
Opik is Open Source! You can find the full source code on [GitHub](https://github.com/comet-ml/opik) and the complete self-hosting guide can be found [here](/self-host/local_deployment.md).
:::

## Overview

The Opik platform allows you to track, view and evaluate your LLM traces during both development and production.

### Development

During development, you can use the platform to log, view and debug your LLM traces:

1. Log traces using:

   a. One of our [integrations](/tracing/integrations/overview.md).

   b. The `@track` decorator for Python, learn more in the [Logging Traces](/tracing/log_traces.mdx) guide.

   c. Using the Typescript Opik SDK, learn more in the [Logging Traces](/tracing/log_traces.mdx#logging-with-the-js--ts-sdk) guide.

2. [Annotate and label traces](/tracing/annotate_traces.md) through the SDK or the UI.

### Evaluation and Testing

Evaluating the output of your LLM calls is critical to ensure that your application is working as expected and can be challenging. Using the Opik platformm, you can:

1. Use one of our [LLM as a Judge evaluators](/evaluation/metrics/overview.md) or [Heuristic evaluators](/evaluation/metrics/heuristic_metrics.md) to score your traces and LLM calls
2. [Store evaluation datasets](/evaluation/manage_datasets.md) in the platform and [run evaluations](/evaluation/evaluate_your_llm.md)
3. Use our [pytest integration](/testing/pytest_integration.md) to track unit test results and compare results between runs

### Production Monitoring

Opik has been designed from the ground up to support high volumes of traces making it the ideal tool for monitoring your production LLM applications. We have stress tested the application and even a small deployment can ingest more than 40 million traces per day !

Our goal is to make it easy for you to monitor your production LLM applications and easily identify any issues with your production LLM application, for this we have included:

1. [Online evaluation metrics](/production/rules.md) that allow you to score all your production traces and easily identify any issues with your production LLM application.
2. [Production monitoring dashboards](/production/production_monitoring.md) that allow you to review your feedback scores, trace count and tokens over time at both a daily and hourly granularity.

## Getting Started

[Comet](https://www.comet.com/site) provides a managed Cloud offering for Opik, simply [create an account](https://www.comet.com/signup?from=llm) to get started.

You can also run Opik locally using our [local installer](/self-host/local_deployment.md). If you are looking for a more production ready deployment, you can also use our [Kubernetes deployment option](/self-host/kubernetes.md).
