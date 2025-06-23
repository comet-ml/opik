def generate_query(output: str, json_schema: str) -> str:
    """
    Generates a query for the LLM to evaluate the structured output compliance.
    """
    return f"""You are a strict JSON validator. Your task is to evaluate if the given `OUTPUT` is a valid JSON that complies with the provided `JSON_SCHEMA`. If no schema is provided, you should only validate the JSON format.
`JSON_SCHEMA`:
```json
{json_schema}
```
`OUTPUT`:
```
{output}
```
Analyze the `OUTPUT` against the `JSON_SCHEMA`.
It is crucial that you provide your answer in the following JSON format:
{{
    "score": <0 if it complies, 1 if it does not>,
    "reason": ["reason 1", "reason 2"]
}}
If it complies, the reason should be an empty list. Reasons amount is not restricted. Output must be JSON format only.
"""