from typing import List, TypedDict, Optional

from opik.evaluation.models import base_model


class FewShotExampleHallucination(TypedDict):
    title: str
    input: str
    context: List[str]
    output: str
    score: float
    reason: str


_CONTEXT_SYSTEM_PROMPT = """You are an expert judge tasked with evaluating the faithfulness of an AI-generated answer to the given context. Analyze the provided INPUT, CONTEXT, and OUTPUT to determine if the OUTPUT contains any hallucinations or unfaithful information.

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

It is crucial that you provide your answer in the following JSON format:
{{
    "score": <your score between 0.0 and 1.0>,
    "reason": ["reason 1", "reason 2"]
}}
Reasons amount is not restricted. Output must be JSON format only.{examples_block}"""

_OUTPUT_SYSTEM_PROMPT = """You are an expert judge tasked with evaluating the factual accuracy and reliability of an AI-generated answer. Analyze the provided INPUT, and OUTPUT to determine if the OUTPUT contains any hallucinations or unfaithful information.

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

It is crucial that you provide your answer in the following JSON format:
{{
    "score": <your score between 0.0 and 1.0>,
    "reason": ["some reason 1", "some reason 2"]
}}
Reasons amount is not restricted. Output must be JSON format only.{examples_block}"""

_CONTEXT_USER_TEMPLATE = """INPUT (for context only, not to be used for faithfulness evaluation):
{input}

CONTEXT:
{context}

OUTPUT:
{output}"""

_OUTPUT_USER_TEMPLATE = """INPUT (for context only, not to be used for faithfulness evaluation):
{input}

OUTPUT:
{output}"""


def _format_examples(
    few_shot_examples: Optional[List[FewShotExampleHallucination]],
    include_context: bool,
) -> str:
    if not few_shot_examples:
        return ""
    rendered = "\n\nEXAMPLES:\n\n".join(
        [
            f"<example>\nInput: {example['input']}\nContext: {example['context']}\n"
            if include_context
            else ""
            f"Output: {example['output']}\n\n"
            f'{{"score": "{example["score"]}", "reason": "{example["reason"]}"}}\n'
            f"</example>"
            for example in few_shot_examples
        ]
    )
    return f"\n\n{rendered}"


def build_messages(
    input: str,
    output: str,
    context: Optional[List[str]] = None,
    few_shot_examples: Optional[List[FewShotExampleHallucination]] = None,
) -> List[base_model.ConversationDict]:
    """Build the [system, user] message pair for a hallucination judgment.

    Static instructions, the JSON output spec, and the few-shot examples (which
    only depend on metric configuration) live in the system message so providers
    can cache the prefix across calls. The per-call ``input``/``context``/``output``
    go in the user message.
    """
    include_context = context is not None
    examples_block = _format_examples(
        few_shot_examples, include_context=include_context
    )

    if include_context:
        system_content = _CONTEXT_SYSTEM_PROMPT.format(examples_block=examples_block)
        user_content = _CONTEXT_USER_TEMPLATE.format(
            input=input, context=context, output=output
        )
    else:
        system_content = _OUTPUT_SYSTEM_PROMPT.format(examples_block=examples_block)
        user_content = _OUTPUT_USER_TEMPLATE.format(input=input, output=output)

    return [
        {"role": "system", "content": system_content},
        {"role": "user", "content": user_content},
    ]
