import json
from typing import Any, List, TypedDict


class Assertion(TypedDict):
    """Represents an assertion to evaluate against the output."""

    name: str
    """The name/identifier of the assertion."""

    description: str
    """Description of what the assertion checks."""


LLM_JUDGE_TEMPLATE = """You are an expert judge tasked with evaluating if an AI agent's output satisfies a set of assertions.

## Input
The INPUT section contains all data that the agent received. This may include the actual user query, conversation history, context, metadata, or other structured information. Identify the core user request within this data.

{input}

## Output
The OUTPUT section contains all data produced by the agent. This may include the agent's response text, tool calls, intermediate results, metadata, or other structured information. Focus on the substantive response when evaluating assertions.

{output}

## Assertions
Evaluate each of the following assertions against the agent's output:

{assertions}

For each assertion, determine if it passes (1) or fails (0).
Also provide a pass_score between 0.0 and 1.0 indicating how close the output was to fulfilling the assertion.

It is crucial that you provide your answer in the following JSON format:
{{
    "results": [
        {{
            "name": "<assertion name>",
            "value": <1 for pass, 0 for fail>,
            "reason": "<brief explanation>",
            "metadata": {{
                "pass_score": <float between 0.0 and 1.0>
            }}
        }}
    ]
}}

Output must be JSON format only. Evaluate ALL assertions provided.
"""


def _format_value(value: Any) -> str:
    """Format a value for inclusion in the prompt."""
    if isinstance(value, str):
        return value
    return json.dumps(value, indent=2, default=str)


def generate_query(
    input: Any,
    output: Any,
    assertions: List[Assertion],
) -> str:
    """
    Generate the LLM query for evaluating assertions.

    Args:
        input: All inputs the agent received. Can be string, dict, list, or any
            JSON-serializable structure.
        output: All outputs from the agent. Can be string, dict, list, or any
            JSON-serializable structure.
        assertions: List of assertions to evaluate.

    Returns:
        The formatted query string.
    """
    assertions_str = "\n".join(
        f"- **{assertion['name']}**: {assertion['description']}"
        for assertion in assertions
    )

    return LLM_JUDGE_TEMPLATE.format(
        input=_format_value(input),
        output=_format_value(output),
        assertions=assertions_str,
    )
