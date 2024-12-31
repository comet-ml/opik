---
sidebar_label: ContextRecall
description: Describes the Context Recall metric
---

# ContextRecall

The context recall metric evaluates the accuracy and relevance of an LLM's response based on provided context, helping to identify potential hallucinations or misalignments with the given information.

## How to use the ContextRecall metric

You can use the `ContextRecall` metric as follows:

```python
from opik.evaluation.metrics import ContextRecall

metric = ContextRecall()

metric.score(
    input="What is the capital of France?",
    output="The capital of France is Paris. It is famous for its iconic Eiffel Tower and rich cultural heritage.",
    expected_output="Paris",
    context=["France is a country in Western Europe. Its capital is Paris, which is known for landmarks like the Eiffel Tower."],
)
```

Asynchronous scoring is also supported with the `ascore` scoring method.

## ContextRecall Prompt

Opik uses an LLM as a Judge to compute context recall, for this we have a prompt template that is used to generate the prompt for the LLM. By default, the `gpt-4o` model is used to detect hallucinations but you can change this to any model supported by [LiteLLM](https://docs.litellm.ai/docs/providers) by setting the `model` parameter. You can learn more about customizing models in the [Customize models for LLM as a Judge metrics](/evaluation/metrics/custom_model.md) section.

The template uses a few-shot prompting technique to compute context recall. The template is as follows:

```
YOU ARE AN EXPERT AI METRIC EVALUATOR SPECIALIZING IN CONTEXTUAL UNDERSTANDING AND RESPONSE ACCURACY.
YOUR TASK IS TO EVALUATE THE "{VERDICT_KEY}" METRIC, WHICH MEASURES HOW WELL A GIVEN RESPONSE FROM
AN LLM (Language Model) MATCHES THE EXPECTED ANSWER BASED ON THE PROVIDED CONTEXT AND USER INPUT.

###INSTRUCTIONS###

1. **Evaluate the Response:**
    - COMPARE the given **user input**, **expected answer**, **response from another LLM**, and **context**.
    - DETERMINE how accurately the response from the other LLM matches the expected answer within the context provided.

2. **Score Assignment:**
    - ASSIGN a **{VERDICT_KEY}** score on a scale from **0.0 to 1.0**:
        - **0.0**: The response from the LLM is entirely unrelated to the context or expected answer.
        - **0.1 - 0.3**: The response is minimally relevant but misses key points or context.
        - **0.4 - 0.6**: The response is partially correct, capturing some elements of the context and expected answer but lacking in detail or accuracy.
        - **0.7 - 0.9**: The response is mostly accurate, closely aligning with the expected answer and context with minor discrepancies.
        - **1.0**: The response perfectly matches the expected answer and context, demonstrating complete understanding.

3. **Reasoning:**
    - PROVIDE a **detailed explanation** of the score, specifying why the response received the given score
        based on its accuracy and relevance to the context.

4. **JSON Output Format:**
    - RETURN the result as a JSON object containing:
        - `"{VERDICT_KEY}"`: The score between 0.0 and 1.0.
        - `"{REASON_KEY}"`: A detailed explanation of the score.

###CHAIN OF THOUGHTS###

1. **Understand the Context:**
    1.1. Analyze the context provided.
    1.2. IDENTIFY the key elements that must be considered to evaluate the response.

2. **Compare the Expected Answer and LLM Response:**
    2.1. CHECK the LLM's response against the expected answer.
    2.2. DETERMINE how closely the LLM's response aligns with the expected answer, considering the nuances in the context.

3. **Assign a Score:**
    3.1. REFER to the scoring scale.
    3.2. ASSIGN a score that reflects the accuracy of the response.

4. **Explain the Score:**
    4.1. PROVIDE a clear and detailed explanation.
    4.2. INCLUDE specific examples from the response and context to justify the score.

###WHAT NOT TO DO###

- **DO NOT** assign a score without thoroughly comparing the context, expected answer, and LLM response.
- **DO NOT** provide vague or non-specific reasoning for the score.
- **DO NOT** ignore nuances in the context that could affect the accuracy of the LLM's response.
- **DO NOT** assign scores outside the 0.0 to 1.0 range.
- **DO NOT** return any output format other than JSON.

###FEW-SHOT EXAMPLES###

{examples_str}

###INPUTS:###
***
Input:
{input}

Output:
{output}

Expected Output:
{expected_output}

Context:
{context}
***
```

with `VERDICT_KEY` being `context_recall_score` and `REASON_KEY` being `reason`.
