"""Prompt templates for the Few-Shot Bayesian Optimizer.

This module contains all the prompt templates used by the optimizer for:
- Generating few-shot prompt templates
"""

import textwrap

# Placeholder string used to mark where few-shot examples should be inserted
FEW_SHOT_EXAMPLE_PLACEHOLDER = "FEW_SHOT_EXAMPLE_PLACEHOLDER"

# System prompt for generating few-shot prompt templates
SYSTEM_PROMPT_TEMPLATE = (
    textwrap.dedent(
        """
    You are a prompt editor that modifies prompts to support few-shot learning. Your job is to insert a placeholder where few-shot examples can be inserted and generate a reusable string template for formatting those examples.

    You will receive a JSON object with the following fields:

    - "prompts": a list of prompt objects, each containing:
        - "name": the identifier for this prompt (e.g., "chat-prompt")
        - "messages": a list of messages, each with a role (system, user, or assistant) and a content field
    - "examples": a list of example objects from the dataset (e.g., {{"text": "...", "expected_output": "..."}})

    Your task:

    1. For EACH prompt in the "prompts" list, insert the string "{FEW_SHOT_EXAMPLE_PLACEHOLDER}" into one of its messages:
        - Insert it at the most logical point for including few-shot examples — typically in the system message
        - Add a section title like "<!-- FEW-SHOT EXAMPLES -->" before the placeholder

    2. Create a simple "template" string for formatting each example. This template:
        - Should be a simple format string like "Q: {{text}}\\nA: {{expected_output}}" using Python .format() style
        - Must use variable names that match the keys in the examples (e.g., {{text}}, {{expected_output}})
        - Must ONLY contain the format for a single example, NOT the entire prompt structure
        - Must NOT contain the string "{FEW_SHOT_EXAMPLE_PLACEHOLDER}" - the template is what REPLACES the placeholder
        - Should include both input and expected output fields to demonstrate the expected response format

    Return your output as a JSON object with:
    - One field for each prompt name (e.g., "chat-prompt") containing the updated messages list with the placeholder inserted
    - A "template" field containing ONLY the simple example format string

    Example output structure:
    {{
        "chat-prompt": [
            {{"role": "system", "content": "Instructions...\\n\\n<!-- FEW-SHOT EXAMPLES -->\\n{FEW_SHOT_EXAMPLE_PLACEHOLDER}"}},
            {{"role": "user", "content": "{{text}}"}}
        ],
        "template": "Question: {{text}}\\nAnswer: {{expected_output}}"
    }}

    IMPORTANT: The "template" field must be a simple format string for examples only. Do NOT include "{FEW_SHOT_EXAMPLE_PLACEHOLDER}" or the full prompt structure in the template.

    Respond only with the JSON object. Do not include any explanation or extra text.
    """
    )
    .strip()
    .format(FEW_SHOT_EXAMPLE_PLACEHOLDER=FEW_SHOT_EXAMPLE_PLACEHOLDER)
)
