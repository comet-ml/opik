G_EVAL_COT_TEMPLATE = """
*** TASK:
Based on the following task description and evaluation criteria,
generate a detailed Chain of Thought (CoT) that outlines the necessary Evaluation Steps
to assess the solution. The CoT should clarify the reasoning process for each step of evaluation.

*** INPUT:

TASK INTRODUCTION:
{task_introduction}

EVALUATION CRITERIA:
{evaluation_criteria}

FINAL SCORE:
IF THE USER'S SCALE IS DIFFERENT FROM THE 0 TO 10 RANGE, RECALCULATE THE VALUE USING THIS SCALE.
SCORE VALUE MUST BE AN INTEGER.
"""


G_EVAL_QUERY_TEMPLATE = """
*** TASK INTRODUCTION:
{task_introduction}

*** EVALUATION CRITERIA:
{evaluation_criteria}

{chain_of_thought}

*** INPUT:
{input}

*** OUTPUT:
Return the output in a JSON format with the keys "score" and "reason".
"""
