export const SYSTEM_PROMPT = `You are an expert judge tasked with evaluating if an AI agent's output satisfies a set of assertions.

For each assertion, provide:
- score: true if the assertion passes, false if it fails
- reason: A brief explanation of your judgment
- confidence: A float between 0.0 and 1.0 indicating how confident you are in your judgment`;

export const USER_PROMPT_TEMPLATE = `## Input
The INPUT section contains all data that the agent received. This may include the actual user query, conversation history, context, metadata, or other structured information. Identify the core user request within this data.

---BEGIN INPUT---
{input}
---END INPUT---

## Output
The OUTPUT section contains all data produced by the agent. This may include the agent's response text, tool calls, intermediate results, metadata, or other structured information. Focus on the substantive response when evaluating assertions.

---BEGIN OUTPUT---
{output}
---END OUTPUT---

## Assertions
Evaluate each of the following assertions against the agent's output.
Use the provided field key as the JSON property name for each assertion result.

{assertions}`;
