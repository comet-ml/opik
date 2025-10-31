from typing import List, Optional

from .schema import FewShotExampleStructuredOutputCompliance


structured_output_compliance_template = """You are an expert in structured data validation. Your task is to determine whether the given OUTPUT complies with the expected STRUCTURE. The structure may be described as a JSON schema, a Pydantic model, or simply implied to be valid JSON.
Guidelines:
1. OUTPUT must be a valid JSON object (not just a string).
2. If a schema is provided, the OUTPUT must match the schema exactly in field names, types, and structure.
3. If no schema is provided, ensure the OUTPUT is a well-formed and parsable JSON.
4. Common formatting issues (missing quotes, incorrect brackets, etc.) should be flagged.
5. Partial compliance is considered non-compliant.
6. Respond only in the specified JSON format.
7. Score should be true if output fully complies, false otherwise.
{examples_str}
EXPECTED STRUCTURE (optional):
{schema}
OUTPUT:
{output}
Respond in the following JSON format:
{{
    "score": <true or false>,
    "reason": ["list of reasons for failure or confirmation"]
}}
"""


def generate_query(
    output: str,
    schema: Optional[str] = None,
    few_shot_examples: Optional[List[FewShotExampleStructuredOutputCompliance]] = None,
) -> str:
    if few_shot_examples is None:
        examples_str = ""
    else:
        examples_str = ""
        if few_shot_examples:
            examples_str = "\n\nEXAMPLES:\n\n" + "\n\n".join(
                [
                    f"<example>\nTitle: {example.title}\nExpected Schema: {example.output_schema or 'None'}\nOutput: {example.output}\n\n"
                    f'{{"score": {str(example.score).lower()}, "reason": ["{example.reason}"]}}\n</example>'
                    for example in few_shot_examples
                ]
            )

    return structured_output_compliance_template.format(
        examples_str=examples_str,
        schema=schema or "(No schema provided — assume valid JSON)",
        output=output,
    )
