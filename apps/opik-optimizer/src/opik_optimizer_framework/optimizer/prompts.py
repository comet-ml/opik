IMPROVE_PROMPT_TEMPLATE = """\
You are an expert prompt engineer. Your task is to improve an LLM prompt.

Here is the current prompt (as a list of messages):
{current_prompt}

The prompt is used with the model: {model}

Generate an improved version of the prompt that is more likely to produce high-quality outputs.
Focus on:
- Clarity and specificity of instructions
- Better structure and organization
- More effective use of the system message
- Appropriate level of detail

Return ONLY the improved prompt as a JSON array of message objects.
Each message must have "role" and "content" fields.
Do not include any explanation or commentary outside the JSON array.
"""
