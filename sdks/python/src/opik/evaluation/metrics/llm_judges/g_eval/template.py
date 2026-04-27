from typing import List

from opik.evaluation.models import base_model


_COT_SYSTEM_PROMPT = """Based on the following task description and evaluation criteria,
generate a detailed Chain of Thought (CoT) that outlines the necessary Evaluation Steps
to assess the solution. The CoT should clarify the reasoning process for each step of evaluation.

FINAL SCORE:
IF THE USER'S SCALE IS DIFFERENT FROM THE 0 TO 10 RANGE, RECALCULATE THE VALUE USING THIS SCALE.
SCORE VALUE MUST BE AN INTEGER."""


_QUERY_SYSTEM_PROMPT = """*** TASK INTRODUCTION:
{task_introduction}

*** EVALUATION CRITERIA:
{evaluation_criteria}

{chain_of_thought}

*** OUTPUT:
Return the output in a JSON format with the keys "score" and "reason".
"""


def build_chain_of_thought_messages(
    task_introduction: str, evaluation_criteria: str
) -> List[base_model.ConversationDict]:
    """Build messages for generating the chain-of-thought used by GEval.

    The CoT is cached per-metric, so caching benefits don't apply here, but the
    [system, user] split keeps the API consistent.
    """
    user_content = (
        "*** INPUT:\n\n"
        f"TASK INTRODUCTION:\n{task_introduction}\n\n"
        f"EVALUATION CRITERIA:\n{evaluation_criteria}"
    )
    return [
        {"role": "system", "content": _COT_SYSTEM_PROMPT},
        {"role": "user", "content": user_content},
    ]


def build_query_messages(
    task_introduction: str,
    evaluation_criteria: str,
    chain_of_thought: str,
    input: str,
) -> List[base_model.ConversationDict]:
    """Build messages for the GEval scoring call.

    System holds the static-per-metric content (task introduction, criteria, CoT,
    output format spec); user holds the per-call output to evaluate.
    """
    system_content = _QUERY_SYSTEM_PROMPT.format(
        task_introduction=task_introduction,
        evaluation_criteria=evaluation_criteria,
        chain_of_thought=chain_of_thought,
    )
    user_content = f"*** INPUT:\n{input}"
    return [
        {"role": "system", "content": system_content},
        {"role": "user", "content": user_content},
    ]
