---
sidebar_label: Hallucination
description: Describes the Hallucination metric
pytest_codeblocks_skip: true
---

# Hallucination

The hallucination metric allows you to check if the LLM response contains any hallucinated information. In order to check for hallucination, you will need to provide the LLM input, LLM output. If the context is provided, this will also be used to check for hallucinations.

## How to use the Hallucination metric

You can use the `Hallucination` metric as follows:

```python
from opik.evaluation.metrics import Hallucination

metric = Hallucination()

metric.score(
    input="What is the capital of France?",
    output="The capital of France is Paris. It is famous for its iconic Eiffel Tower and rich cultural heritage.",
)
```

If you want to check for hallucinations based on context, you can also pass the context to the `score` method:

```python
metric.score(
    input="What is the capital of France?",
    output="The capital of France is Paris. It is famous for its iconic Eiffel Tower and rich cultural heritage.",
    context=["France is a country in Western Europe. Its capital is Paris, which is known for landmarks like the Eiffel Tower."],
)
```

Asynchronous scoring is also supported with the `ascore` scoring method.

:::tip
The hallucination score is either `0` or `1`. A score of `0` indicates that no hallucinations were detected, a score of `1` indicates that hallucinations were detected.
:::

## Hallucination Prompt

Opik uses an LLM as a Judge to detect hallucinations, for this we have a prompt template that is used to generate the prompt for the LLM. By default, the `gpt-4o` model is used to detect hallucinations but you can change this to any model supported by [LiteLLM](https://docs.litellm.ai/docs/providers) by setting the `model` parameter. You can learn more about customizing models in the [Customize models for LLM as a Judge metrics](/evaluation/metrics/custom_model.md) section.

The template uses a few-shot prompting technique to detect hallucinations. The template is as follows:

```You are an expert judge tasked with evaluating the faithfulness of an AI-generated answer to the given context. Analyze the provided INPUT, CONTEXT, and OUTPUT to determine if the OUTPUT contains any hallucinations or unfaithful information.

Guidelines:
1. The OUTPUT must not introduce new information beyond what's provided in the CONTEXT.
2. The OUTPUT must not contradict any information given in the CONTEXT.
3. The OUTPUT should not contradict well-established facts or general knowledge.
4. Ignore the INPUT when evaluating faithfulness; it's provided for context only.
5. Consider partial hallucinations where some information is correct but other parts are not.
6. Pay close attention to the subject of statements. Ensure that attributes, actions, or dates are correctly associated with the right entities (e.g., a person vs. a TV show they star in).
7. Be vigilant for subtle misattributions or conflations of information, even if the date or other details are correct.
8. Check that the OUTPUT doesn't oversimplify or generalize information in a way that changes its meaning or accuracy.

Analyze the text thoroughly and assign a hallucination score between 0 and 1, where:
- 0.0: The OUTPUT is entirely faithful to the CONTEXT
- 1.0: The OUTPUT is entirely unfaithful to the CONTEXT

{examples_str}

INPUT (for context only, not to be used for faithfulness evaluation):
{input}

CONTEXT:
{context}

OUTPUT:
{output}

It is crucial that you provide your answer in the following JSON format:
{{
    "score": <your score between 0.0 and 1.0>,
    "reason": ["reason 1", "reason 2"]
}}
Reasons amount is not restricted. Output must be JSON format only.
```
