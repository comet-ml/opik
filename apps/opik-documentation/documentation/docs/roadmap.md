---
sidebar_label: Roadmap
description: Opik Roadmap
---

# Roadmap

Opik is [Open-Source](https://github.com/comet-opik/opik) and is under very active development. We use the feedback from the Opik community to drive the roadmap, this is very much a living document that will change as we release new features and learn about new ways to improve the product.

:::tip

If you have any ideas or suggestions for the roadmap, you can create a [new Feature Request issue](https://github.com/comet-ml/opik/issues/new/choose) in the Opik Github repo.

:::

## What are we currently working on ?

We are currently working on both improving existing features and developing new features:

- **Tracing**:
  - [x] Integration with Dify
  - [x] DSPY integration
  - [x] Guardrails integration
  - [x] Crew AI integration
  - [ ] Typescript / Javascript SDK
- **Evaluation**:
  - [ ] Update to evaluation docs
  - [ ] New reference based evaluation metrics (ROUGE, BLEU, etc)
- **New features**:
  - [x] Prompt playground for evaluating prompt templates
  - [ ] Running evaluations from the Opik platform
  - [x] Online evaluation using LLM as a Judge metrics, allows Opik to score traces logged to the platform using LLM as a Judge metrics
  - [ ] Online evaluation using code metrics

You can view all the features we have released in our [changelog](/changelog.md).

## What is planned next ?

We are currently working on both improvements to the existing features in Opik as well as new features:

- **Improvements**:
  - [ ] Introduce a "Pretty" format mode for trace inputs and outputs
  - [ ] Improved display of chat conversations
  - [ ] Add support for trace attachments to track PDFs, audio, video, etc associated with a trace
  - [ ] Agent replay feature
- **Evaluation**:
  - [ ] Dataset versioning
  - [ ] Prompt optimizations tools for both the playground and the Python SDK
  - [ ] Support for agents in the Opik playground
- **Production**:
  - [ ] Introduce Guardrails metrics to the Opik platform

You can vote on these items as well as suggest new ideas on our [Github Issues page](https://github.com/comet-ml/opik/issues/new/choose).

## Provide your feedback

We are relying on your feedback to shape the roadmap and decided which features to work on next. You can upvote existing ideas or even
add your own on [Github Issues](https://github.com/comet-ml/opik/issues/).

You can also find a list of all the features we have released in our [weekly release notes](/changelog.md).
