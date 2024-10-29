---
sidebar_label: ContextPrecision
---

# ContextPrecision

The context precision metric evaluates the accuracy and relevance of an LLM's response based on provided context, helping to identify potential hallucinations or misalignments with the given information.

## How to use the ContextPrecision metric

You can use the `ContextPrecision` metric as follows:

```python
from opik.evaluation.metrics import ContextPrecision

metric = ContextPrecision()

metric.score(
    input="What is the capital of France?",
    output="The capital of France is Paris. It is famous for its iconic Eiffel Tower and rich cultural heritage.",
    expected_output="Paris",
    context=["France is a country in Western Europe. Its capital is Paris, which is known for landmarks like the Eiffel Tower."],
)
```

Asynchronous scoring is also supported with the `ascore` scoring method.

## ContextPrecision Prompt

Opik uses an LLM as a Judge to compute context precision, for this we have a prompt template that is used to generate the prompt for the LLM. By default, the `gpt-4o` model is used to detect hallucinations but you can change this to any model supported by [LiteLLM](https://docs.litellm.ai/docs/providers) by setting the `model_name` parameter.

The template uses a few-shot prompting technique to compute context precision. The template is as follows:

```
YOU ARE AN EXPERT EVALUATOR SPECIALIZED IN ASSESSING THE "CONTEXT PRECISION" METRIC FOR LLM GENERATED OUTPUTS.
YOUR TASK IS TO EVALUATE HOW PRECISELY A GIVEN ANSWER FROM AN LLM FITS THE EXPECTED ANSWER, GIVEN THE CONTEXT AND USER INPUT.

###INSTRUCTIONS###

1. **EVALUATE THE CONTEXT PRECISION:**
    - **ANALYZE** the provided user input, expected answer, answer from another LLM, and the context.
    - **COMPARE** the answer from the other LLM with the expected answer, focusing on how well it aligns in terms of context, relevance, and accuracy.
    - **ASSIGN A SCORE** from 0.0 to 1.0 based on the following scale:

###SCALE FOR CONTEXT PRECISION METRIC (0.0 - 1.0)###

- **0.0:** COMPLETELY INACCURATE – The LLM's answer is entirely off-topic, irrelevant, or incorrect based on the context and expected answer.
- **0.2:** MOSTLY INACCURATE – The answer contains significant errors, misunderstanding of the context, or is largely irrelevant.
- **0.4:** PARTIALLY ACCURATE – Some correct elements are present, but the answer is incomplete or partially misaligned with the context and expected answer.
- **0.6:** MOSTLY ACCURATE – The answer is generally correct and relevant but may contain minor errors or lack complete precision in aligning with the expected answer.
- **0.8:** HIGHLY ACCURATE – The answer is very close to the expected answer, with only minor discrepancies that do not significantly impact the overall correctness.
- **1.0:** PERFECTLY ACCURATE – The LLM's answer matches the expected answer precisely, with full adherence to the context and no errors.

2. **PROVIDE A REASON FOR THE SCORE:**
    - **JUSTIFY** why the specific score was given, considering the alignment with context, accuracy, relevance, and completeness.

3. **RETURN THE RESULT IN A JSON FORMAT** as follows:
    - `"{VERDICT_KEY}"`: The score between 0.0 and 1.0.
    - `"{REASON_KEY}"`: A detailed explanation of why the score was assigned.

###WHAT NOT TO DO###
- **DO NOT** assign a high score to answers that are off-topic or irrelevant, even if they contain some correct information.
- **DO NOT** give a low score to an answer that is nearly correct but has minor errors or omissions; instead, accurately reflect its alignment with the context.
- **DO NOT** omit the justification for the score; every score must be accompanied by a clear, reasoned explanation.
- **DO NOT** disregard the importance of context when evaluating the precision of the answer.
- **DO NOT** assign scores outside the 0.0 to 1.0 range.
- **DO NOT** return any output format other than JSON.

###FEW-SHOT EXAMPLES###

{examples_str}

NOW, EVALUATE THE PROVIDED INPUTS AND CONTEXT TO DETERMINE THE CONTEXT PRECISION SCORE.

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

with `VERDICT_KEY` being `context_precision_score` and `REASON_KEY` being `reason`.
