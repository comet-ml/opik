from typing import List, TypedDict, Optional


class FewShotExampleHallucination(TypedDict):
    title: str
    input: str
    context: List[str]
    output: str
    score: float
    reason: str


context_hallucination_template = """You are an expert judge tasked with evaluating the faithfulness of an AI-generated answer to the given context. Analyze the provided INPUT, CONTEXT, and OUTPUT to determine if the OUTPUT contains any hallucinations or unfaithful information.

Guidelines:
1. The OUTPUT must not introduce new information beyond what's provided in the CONTEXT.
2. The OUTPUT must not contradict any information given in the CONTEXT.
3. The OUTPUT should not contradict well-established facts or general knowledge.
4. Ignore the INPUT when evaluating faithfulness; it's provided for context only.
5. Consider partial hallucinations where some information is correct but other parts are not.
6. Pay close attention to the subject of statements. Ensure that attributes, actions, or dates are correctly associated with the right entities (e.g., a person vs. a TV show they star in).
7. Be vigilant for subtle misattributions or conflations of information, even if the date or other details are correct.
8. Check that the OUTPUT doesn't oversimplify or generalize information in a way that changes its meaning or accuracy.

Analyze the text thoroughly and assign a hallucination score between 0 and 1, where:
- 0.0: The OUTPUT is entirely faithful to the CONTEXT
- 1.0: The OUTPUT is entirely unfaithful to the CONTEXT

{examples_str}

INPUT (for context only, not to be used for faithfulness evaluation):
{input}

CONTEXT:
{context}

OUTPUT:
{output}

It is crucial that you provide your answer in the following JSON format:
{{
    "score": <your score between 0.0 and 1.0>,
    "reason": ["reason 1", "reason 2"]
}}
Reasons amount is not restricted. Output must be JSON format only.
"""

output_hallucination_template = """You are an expert judge tasked with evaluating the factual accuracy and reliability of an AI-generated answer. Analyze the provided INPUT, and OUTPUT to determine if the OUTPUT contains any hallucinations or unfaithful information.

Guidelines:
1. Evaluate the OUTPUT based on generally accepted facts and reliable information.
2. The OUTPUT should not contradict well-established facts or general knowledge.
3. Ignore the INPUT when evaluating faithfulness; it's provided for context only.
4. Consider partial hallucinations where some information is correct but other parts are not.
5. Pay close attention to the subject of statements. Ensure that attributes, actions, or dates are correctly associated with the right entities (e.g., a person vs. a TV show they star in).
6. Be vigilant for subtle misattributions or conflations of information, even if the date or other details are correct.
7. Check that the OUTPUT doesn't oversimplify or generalize information in a way that changes its meaning or accuracy.

Analyze the text thoroughly and assign a hallucination score between 0 and 1, where:
- 0.0: The OUTPUT is entirely faithful
- 1.0: The OUTPUT is entirely unfaithful

{examples_str}

INPUT (for context only, not to be used for faithfulness evaluation):
{input}

OUTPUT:
{output}

It is crucial that you provide your answer in the following JSON format:
{{
    "score": <your score between 0.0 and 1.0>,
    "reason": ["some reason 1", "some reason 2"]
}}
Reasons amount is not restricted. Output must be JSON format only.
"""


def generate_query(
    input: str,
    output: str,
    context: Optional[List[str]] = None,
    few_shot_examples: Optional[List[FewShotExampleHallucination]] = None,
) -> str:
    if few_shot_examples is None:
        examples_str = ""
    else:
        examples_str = "\n\nEXAMPLES:\n\n".join(
            [
                f"<example>\nInput: {example['input']}\nContext: {example['context']}\n"
                if context is not None
                else ""
                f"Output: {example['output']}\n\n"
                f'{{"score": "{example["score"]}", "reason": "{example["reason"]}"}}\n'
                f"</example>"
                for i, example in enumerate(few_shot_examples)
            ]
        )

    if context is not None:
        return context_hallucination_template.format(
            examples_str=examples_str,
            input=input,
            context=context,
            output=output,
        )
    return output_hallucination_template.format(
        examples_str=examples_str,
        input=input,
        output=output,
    )
