export const SYSTEM_PROMPT = `You are an expert judge tasked with evaluating if an AI agent's output satisfies a set of assertions.

For each assertion, provide:
- score: true if the assertion passes, false if it fails
- reason: A brief explanation of your judgment
- confidence: A float between 0.0 and 1.0 indicating how confident you are in your judgment`;

export const USER_PROMPT_TEMPLATE = `## Input
{input}

## Output
{output}

## Assertions
Evaluate each of the following assertions against the agent's output:
{assertions}`;
