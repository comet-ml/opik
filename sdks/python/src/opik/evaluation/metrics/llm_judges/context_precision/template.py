from typing import List, TypedDict


class FewShotExampleContextPrecision(TypedDict):
    title: str
    input: str
    expected_output: str
    context: str
    output: str
    context_precision_score: float
    reason: str


FEW_SHOT_EXAMPLES: List[FewShotExampleContextPrecision] = [
    {
        "title": "Low ContextPrecision Score",
        "input": "What is the capital of France?",
        "expected_output": "The capital of France is Paris.",
        "context": "The user is asking about the capital city of a European country.",
        "output": "The capital of Italy is Rome.",
        "context_precision_score": 0.0,
        "reason": "The answer provided by the LLM is completely inaccurate as it refers to the wrong country and does not address the user's question about France.",
    },
    {
        "title": "Medium ContextPrecision Score",
        "input": "What is the capital of France?",
        "expected_output": "The capital of France is Paris.",
        "context": "The user is asking for the capital city of France.",
        "output": "Paris is a city in Europe.",
        "context_precision_score": 0.4,
        "reason": "While the answer mentions Paris, it fails to clearly identify it as the capital of France, thus providing only partial accuracy.",
    },
    {
        "title": "High ContextPrecision Score",
        "input": "What is the capital of France?",
        "expected_output": "The capital of France is Paris.",
        "context": "The user is asking about the capital city of France.",
        "output": "The capital of France is Paris.",
        "context_precision_score": 1.0,
        "reason": "The answer perfectly matches the expected response, fully aligning with the context and providing the correct information.",
    },
]


def generate_query(
    input: str,
    output: str,
    expected_output: str,
    context: List[str],
    few_shot_examples: List[FewShotExampleContextPrecision] = FEW_SHOT_EXAMPLES,
) -> str:
    examples_str = "\n\n".join(
        [
            f"#### Example {i + 1}: {example['title']}\n\n"
            f'- **Input:** "{example["input"]}"\n'
            f'- **Output:** "{example["output"]}"\n'
            f'- **Expected Output:** "{example["expected_output"]}"\n'
            f'- **Context:** "{example["context"]}"\n'
            f"- **Result:**\n"
            f"  ```json\n"
            f"  {{\n"
            f'    "context_precision_score": {example["context_precision_score"]},\n'
            f'    "reason": "{example["reason"]}"\n'
            f"  }}\n"
            f"  ```"
            for i, example in enumerate(few_shot_examples)
        ]
    )

    return f"""YOU ARE AN EXPERT EVALUATOR SPECIALIZED IN ASSESSING THE "CONTEXT PRECISION" METRIC FOR LLM GENERATED OUTPUTS.
YOUR TASK IS TO EVALUATE HOW PRECISELY A GIVEN ANSWER FROM AN LLM FITS THE EXPECTED ANSWER, GIVEN THE CONTEXT AND USER INPUT.

###INSTRUCTIONS###

1. **EVALUATE THE CONTEXT PRECISION:**
    - **ANALYZE** the provided user input, expected answer, answer from another LLM, and the context.
    - **COMPARE** the answer from the other LLM with the expected answer, focusing on how well it aligns in terms of context, relevance, and accuracy.
    - **ASSIGN A SCORE** from 0.0 to 1.0 based on the following scale:

###SCALE FOR CONTEXT PRECISION METRIC (0.0 - 1.0)###

- **0.0:** COMPLETELY INACCURATE - The LLM's answer is entirely off-topic, irrelevant, or incorrect based on the context and expected answer.
- **0.2:** MOSTLY INACCURATE - The answer contains significant errors, misunderstanding of the context, or is largely irrelevant.
- **0.4:** PARTIALLY ACCURATE - Some correct elements are present, but the answer is incomplete or partially misaligned with the context and expected answer.
- **0.6:** MOSTLY ACCURATE - The answer is generally correct and relevant but may contain minor errors or lack complete precision in aligning with the expected answer.
- **0.8:** HIGHLY ACCURATE - The answer is very close to the expected answer, with only minor discrepancies that do not significantly impact the overall correctness.
- **1.0:** PERFECTLY ACCURATE - The LLM's answer matches the expected answer precisely, with full adherence to the context and no errors.

2. **PROVIDE A REASON FOR THE SCORE:**
    - **JUSTIFY** why the specific score was given, considering the alignment with context, accuracy, relevance, and completeness.

3. **RETURN THE RESULT IN A JSON FORMAT** as follows:
    - `"context_precision_score"`: The score between 0.0 and 1.0.
    - `"reason"`: A detailed explanation of why the score was assigned.

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
    """
