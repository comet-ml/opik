from typing import List, TypedDict

HALLUCINATION_VERDICT = "hallucinated"
FACTUAL_VERDICT = "factual"

VERDICT_KEY = "verdict"
REASON_KEY = "reason"


class FewShotExampleHallucination(TypedDict):
    title: str
    input: str
    context: List[str]
    output: str
    verdict: str
    reason: str


def generate_query(
    input: str,
    output: str,
    context: List[str],
    few_shot_examples: List[FewShotExampleHallucination],
) -> str:
    if len(few_shot_examples) == 0:
        examples_str = ""
    else:
        examples_str = "\n\nEXAMPLES:\n\n".join(
            [
                f"<example>\n"
                f"Input: {example['input']}\n"
                f"Context: {example['context']}\n"
                f"Output: {example['output']}\n\n"
                f"{{\"{VERDICT_KEY}\": \"{example['verdict']}\", \"{REASON_KEY}\": \"{example['reason']}\"}}\n"
                f"</example>"
                for i, example in enumerate(few_shot_examples)
            ]
        )

    return f"""You are an expert judge tasked with evaluating the faithfulness of an AI-generated answer to the given context. Analyze the provided INPUT, CONTEXT, and OUTPUT to determine if the OUTPUT contains any hallucinations or unfaithful information.

Guidelines:
1. The OUTPUT must not introduce new information beyond what's provided in the CONTEXT.
2. The OUTPUT must not contradict any information given in the CONTEXT.
3. Ignore the INPUT when evaluating faithfulness; it's provided for context only.
4. Consider partial hallucinations where some information is correct but other parts are not.
5. Pay close attention to the subject of statements. Ensure that attributes, actions, or dates are correctly associated with the right entities (e.g., a person vs. a TV show they star in).
6. Be vigilant for subtle misattributions or conflations of information, even if the date or other details are correct.
7. Check that the OUTPUT doesn't oversimplify or generalize information in a way that changes its meaning or accuracy.

Verdict options:
- "{FACTUAL_VERDICT}": The OUTPUT is entirely faithful to the CONTEXT.
- "{HALLUCINATION_VERDICT}": The OUTPUT contains hallucinations or unfaithful information.

{examples_str}

INPUT (for context only, not to be used for faithfulness evaluation):
{input}

CONTEXT:
{context}

OUTPUT:
{output}

Provide your verdict in JSON format:
{{
    "{VERDICT_KEY}": <your verdict>,
    "{REASON_KEY}": [
        <list your reasoning as bullet points>
    ]
}}
    """
