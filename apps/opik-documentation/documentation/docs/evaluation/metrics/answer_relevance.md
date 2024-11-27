---
sidebar_label: AnswerRelevance
---

# Answer Relevance

The Answer Relevance metric allows you to evaluate how relevant and appropriate the LLM's response is to the given input question or prompt. To assess the relevance of the answer, you will need to provide the LLM input (question or prompt) and the LLM output (generated answer). Unlike the Hallucination metric, the Answer Relevance metric focuses on the appropriateness and pertinence of the response rather than factual accuracy.

You can use the `AnswerRelevance` metric as follows:

```python
from opik.evaluation.metrics import AnswerRelevance

metric = AnswerRelevance()

metric.score(
    input="What is the capital of France?",
    output="The capital of France is Paris. It is famous for its iconic Eiffel Tower and rich cultural heritage.",
    context=["France is a country in Western Europe. Its capital is Paris, which is known for landmarks like the Eiffel Tower."],
)
```

Asynchronous scoring is also supported with the `ascore` scoring method.

## Detecting answer relevance

Opik uses an LLM as a Judge to detect answer relevance, for this we have a prompt template that is used to generate the prompt for the LLM. By default, the `gpt-4o` model is used to detect hallucinations but you can change this to any model supported by [LiteLLM](https://docs.litellm.ai/docs/providers) by setting the `model` parameter. You can learn more about customizing models in the [Customize models for LLM as a Judge metrics](/evaluation/metrics/custom_model.md) section.

The template uses a few-shot prompting technique to detect answer relevance. The template is as follows:

```
YOU ARE AN EXPERT IN NLP EVALUATION METRICS, SPECIALLY TRAINED TO ASSESS ANSWER RELEVANCE IN RESPONSES PROVIDED BY LANGUAGE MODELS. YOUR TASK IS TO EVALUATE THE RELEVANCE OF A GIVEN ANSWER FROM ANOTHER LLM BASED ON THE USER'S INPUT AND CONTEXT PROVIDED.

###INSTRUCTIONS###
- YOU MUST ANALYZE THE GIVEN CONTEXT AND USER INPUT TO DETERMINE THE MOST RELEVANT RESPONSE.
- EVALUATE THE ANSWER FROM THE OTHER LLM BASED ON ITS ALIGNMENT WITH THE USER'S QUERY AND THE CONTEXT.
- ASSIGN A RELEVANCE SCORE BETWEEN 0.0 (COMPLETELY IRRELEVANT) AND 1.0 (HIGHLY RELEVANT).
- RETURN THE RESULT AS A JSON OBJECT, INCLUDING THE SCORE AND A BRIEF EXPLANATION OF THE RATING.
###CHAIN OF THOUGHTS###
1. **Understanding the Context and Input:**
    1.1. READ AND COMPREHEND THE CONTEXT PROVIDED.
    1.2. IDENTIFY THE KEY POINTS OR QUESTIONS IN THE USER'S INPUT THAT THE ANSWER SHOULD ADDRESS.
2. **Evaluating the Answer:**
    2.1. COMPARE THE CONTENT OF THE ANSWER TO THE CONTEXT AND USER INPUT.
    2.2. DETERMINE WHETHER THE ANSWER DIRECTLY ADDRESSES THE USER'S QUERY OR PROVIDES RELEVANT INFORMATION.
    2.3. CONSIDER ANY EXTRANEOUS OR OFF-TOPIC INFORMATION THAT MAY DECREASE RELEVANCE.
3. **Assigning a Relevance Score:**
    3.1. ASSIGN A SCORE BASED ON HOW WELL THE ANSWER MATCHES THE USER'S NEEDS AND CONTEXT.
    3.2. JUSTIFY THE SCORE WITH A BRIEF EXPLANATION THAT HIGHLIGHTS THE STRENGTHS OR WEAKNESSES OF THE ANSWER.
4. **Generating the JSON Output:**
    4.1. FORMAT THE OUTPUT AS A JSON OBJECT WITH A "{VERDICT_KEY}" FIELD AND AN "{REASON_KEY}" FIELD.
    4.2. ENSURE THE SCORE IS A FLOATING-POINT NUMBER BETWEEN 0.0 AND 1.0.
###WHAT NOT TO DO###
- DO NOT GIVE A SCORE WITHOUT FULLY ANALYZING BOTH THE CONTEXT AND THE USER INPUT.
- AVOID SCORES THAT DO NOT MATCH THE EXPLANATION PROVIDED.
- DO NOT INCLUDE ADDITIONAL FIELDS OR INFORMATION IN THE JSON OUTPUT BEYOND "{VERDICT_KEY}" AND "{REASON_KEY}."
- NEVER ASSIGN A PERFECT SCORE UNLESS THE ANSWER IS FULLY RELEVANT AND FREE OF ANY IRRELEVANT INFORMATION.
###EXAMPLE OUTPUT FORMAT###
{{
    "{VERDICT_KEY}": 0.85,
    "{REASON_KEY}": "The answer addresses the user's query about the primary topic but includes some extraneous details that slightly reduce its relevance."
}}
###INPUTS:###
***
User input:
{user_input}
Answer:
{answer}
Contexts:
{contexts}
***
```
