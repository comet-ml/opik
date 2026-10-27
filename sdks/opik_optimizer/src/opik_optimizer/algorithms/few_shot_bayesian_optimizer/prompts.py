"""Prompt templates for the Few-Shot Bayesian Optimizer.

This module contains all the prompt templates used by the optimizer for:
- Generating few-shot prompt templates
"""

# Placeholder string used to mark where few-shot examples should be inserted
FEW_SHOT_EXAMPLE_PLACEHOLDER = "FEW_SHOT_EXAMPLE_PLACEHOLDER"

# System prompt for generating few-shot prompt templates
SYSTEM_PROMPT_TEMPLATE = "You are a prompt editor creating a reusable few-shot template. The request supplies named prompts with editable segments and example_fields. Native evidence shows rendered inputs and images.\nFor each named prompt return targeted text edits, each with segment_id and text. Insert {placeholder} into an allowed text segment at a useful place for examples, preserving all original variables. Do not regenerate messages, roles or media.\nAlso return a template string formatting one example. Use only exact keys from example_fields, with single-brace placeholders. The template must not contain {placeholder}. Include the relevant inputs and expected output, but never embed image bytes or URLs in text. Images are carried separately as native content blocks.\nReturn the requested JSON schema: each prompt-name field contains its text-edit list and template contains the single-example format string. Return JSON only."


def build_system_prompt_template(
    template: str = SYSTEM_PROMPT_TEMPLATE,
    placeholder: str = FEW_SHOT_EXAMPLE_PLACEHOLDER,
) -> str:
    """Build the system prompt template with placeholder substitution.

    Args:
        template: The system prompt template string (defaults to SYSTEM_PROMPT_TEMPLATE)
        placeholder: The placeholder string to substitute (defaults to FEW_SHOT_EXAMPLE_PLACEHOLDER)

    Returns:
        The template with {placeholder} replaced by the actual placeholder value
    """
    return template.replace("{placeholder}", placeholder)
