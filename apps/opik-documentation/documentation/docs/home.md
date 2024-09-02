---
sidebar_position: 1
slug: /
sidebar_label: Home
---

# Comet Opik

The LLM Evaluation platform allows you log, view and evaluate your LLM traces during both development and production. Using the platform and our LLM as a Judge evaluators, you can identify and fix issues in your LLM application.

![LLM Evaluation Platform](/img/home/traces_page_with_sidebar.png)

# Overview

## Development

During development, you can use the platform to log, view and debug your LLM traces:

1. Log traces using:
    a. One of our [integrations](./)
    b. The `@track` decorator for Python
    c. The [Rest API](./)
2. Review and debug traces in the [Tracing UI](./)
3. [Annotate and label traces](./) through the UI

## Evaluation and Testing

Evaluating the output of your LLM calls is critical to ensure that your application is working as expected and can be challenging. Using the Comet LLM Evaluation platformm, you can:

1. Use one of our [LLM as a Judge evaluators](./) or [Heuristic evaluators](./) to score your traces and LLM calls
2. [Store evaluation datasets](./) in the platform and [run evaluations](./)
3. Use our [pytest integration](./) to track unit test results and compare results between runs


## Monitoring

You can use the LLM platform to monitor your LLM applications in production, both the SDK and the Backend have been designed to support high volumes of requests.

The platform allows you:

1. Track all LLM calls and traces using our [Python SDK](./) and a [Rest API](./)
2. View, filter and analyze traces in our [Tracing UI](./)
3. Update evaluation datasets with [failed traces](./)



# Getting Started

The Comet LLM Evaluation platform allows you log, view and evaluate your LLM traces during both development and production.