---
sidebar_label: Usefulness
description: Describes the Usefulness metric
pytest_codeblocks_skip: true
---

# Usefulness

The usefulness metric allows you to evaluate how useful an LLM response is given an input. It uses a language model to assess the usefulness and provides a score between 0.0 and 1.0, where higher values indicate higher usefulness. Along with the score, it provides a detailed explanation of why that score was assigned.

## How to use the Usefulness metric

You can use the `Usefulness` metric as follows:

```python
from opik.evaluation.metrics import Usefulness

metric = Usefulness()

result = metric.score(
    input="How can I optimize the performance of my Python web application?",
    output="To optimize your Python web application's performance, focus on these key areas:\n1. Database optimization: Use connection pooling, index frequently queried fields, and cache common queries\n2. Caching strategy: Implement Redis or Memcached for session data and frequently accessed content\n3. Asynchronous operations: Use async/await for I/O-bound operations to handle more concurrent requests\n4. Code profiling: Use tools like cProfile to identify bottlenecks in your application\n5. Load balancing: Distribute traffic across multiple server instances for better scalability",
)

print(result.value)  # A float between 0.0 and 1.0
print(result.reason)  # Explanation for the score
```

Asynchronous scoring is also supported with the `ascore` scoring method.

## Understanding the scores

The usefulness score ranges from 0.0 to 1.0:
- Scores closer to 1.0 indicate that the response is highly useful, directly addressing the input query with relevant and accurate information
- Scores closer to 0.0 indicate that the response is less useful, possibly being off-topic, incomplete, or not addressing the input query effectively

Each score comes with a detailed explanation (`result.reason`) that helps understand why that particular score was assigned.

## Usefulness Prompt

Opik uses an LLM as a Judge to evaluate usefulness, for this we have a prompt template that is used to generate the prompt for the LLM. By default, the `gpt-4o` model is used to evaluate responses but you can change this to any model supported by [LiteLLM](https://docs.litellm.ai/docs/providers) by setting the `model` parameter. You can learn more about customizing models in the [Customize models for LLM as a Judge metrics](/evaluation/metrics/custom_model.md) section.

The template is as follows:

```
You are an impartial judge tasked with evaluating the quality and usefulness of AI-generated responses.
Your evaluation should consider the following key factors:
- Helpfulness: How well does it solve the user's problem?
- Relevance: How well does it address the specific question?
- Accuracy: Is the information correct and reliable?
- Depth: Does it provide sufficient detail and explanation?
- Creativity: Does it offer innovative or insightful perspectives when appropriate?
- Level of detail: Is the amount of detail appropriate for the question?

###EVALUATION PROCESS###

1. **ANALYZE** the user's question and the AI's response carefully
2. **EVALUATE** how well the response meets each of the criteria above
3. **CONSIDER** the overall effectiveness and usefulness of the response
4. **PROVIDE** a clear, objective explanation for your evaluation
5. **SCORE** the response on a scale from 0.0 to 1.0:
   - 1.0: Exceptional response that excels in all criteria
   - 0.8: Excellent response with minor room for improvement
   - 0.6: Good response that adequately addresses the question
   - 0.4: Fair response with significant room for improvement
   - 0.2: Poor response that barely addresses the question
   - 0.0: Completely inadequate or irrelevant response

###OUTPUT FORMAT###

Your evaluation must be provided as a JSON object with exactly two fields:
- "score": A float between 0.0 and 1.0
- "reason": A brief, objective explanation justifying your score based on the criteria above

Now, please evaluate the following:

User Question: {input}
AI Response: {output}

Provide your evaluation in the specified JSON format.
```
