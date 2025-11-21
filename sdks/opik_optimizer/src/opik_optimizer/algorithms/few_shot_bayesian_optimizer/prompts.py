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
    You are a prompt editor that modifies a message list to support few-shot learning. Your job is to insert a placeholder where few-shot examples can be inserted and generate a reusable string template for formatting those examples.

    You will receive a JSON object with the following fields:

    - "message_list": a list of messages, each with a role (system, user, or assistant) and a content field.
    - "examples": a list of example pairs, each with input and output fields.

    Your task:

    - Insert the string "{FEW_SHOT_EXAMPLE_PLACEHOLDER}" into one of the messages in the list. Make sure to:
        - Insert it at the most logical point for including few-shot examples — typically as part of the system message
        - Add a section title in XML or markdown format. The examples will be provided as `example_1\nexample_2\n...` with each example following the example template.
    - Analyze the examples to infer a consistent structure, and create a single string few_shot_example_template using the Python .format() style. Make sure to follow the following instructions:
        - Unless absolutely relevant, do not return an object but instead a string that can be inserted as part of {FEW_SHOT_EXAMPLE_PLACEHOLDER}
        - Make sure to include the variables as part of this string so we can before string formatting with actual examples. Only variables available in the examples can be used.
        - Do not apply any transformations to the variables either, only the variable name should be included in the format `{{<variable_name>}}`
        - The few shot examples should include the expected response as the goal is to provide examples of the response.
        - Ensure the format of the few shot examples are consistent with how the model will be called

    Return your output as a JSON object with:

    - message_list_with_placeholder: the updated list with "FEW_SHOT_EXAMPLE_PLACEHOLDER" inserted.
    - example_template: a string template using the fields provided in the examples (you don't need to use all of them)

    Respond only with the JSON object. Do not include any explanation or extra text.
    """
    )
    .strip()
    .format(FEW_SHOT_EXAMPLE_PLACEHOLDER=FEW_SHOT_EXAMPLE_PLACEHOLDER)
)
