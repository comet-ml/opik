from typing import Any, Dict, List, Tuple, Union, Optional

import uuid
import dspy
import re
import random

from dspy.signatures.signature import make_signature


class State(dict):
    def __getattr__(self, key):
        try:
            return self[key]
        except KeyError as e:
            raise AttributeError(e)

    def __setattr__(self, key, value):
        self[key] = value

    def __delattr__(self, key):
        try:
            del self[key]
        except KeyError as e:
            raise AttributeError(e)


def create_dspy_signature(
    input: str,
    output: str,
    prompt: str = None,
):
    """
    Create a dspy Signature given inputs, outputs, prompt
    """
    # FIXME: allow multiple inputs, input/ouput descriptions
    return make_signature(
        signature={input: (str, dspy.InputField()), output: (str, dspy.OutputField())},
        instructions=prompt,
    )


def opik_metric_to_dspy(metric, output):
    answer_field = output

    def opik_metric_score_wrapper(example, prediction, trace=None):
        # Extract the input from the example
        input_text = getattr(example, "input", "")
        if isinstance(input_text, list):
            input_text = input_text[0] if input_text else ""

        # Extract the expected output
        expected_output = getattr(example, answer_field, "")
        if isinstance(expected_output, list):
            expected_output = expected_output[0] if expected_output else ""

        # Get the model output
        model_output = getattr(prediction, answer_field, "")

        # Create a result dictionary with all required fields
        result = {
            "input": input_text,
            "output": model_output,
            "expected_output": expected_output,
            "reference": expected_output,
            "context": getattr(example, "context", input_text),
        }

        try:
            # Calculate the score using the metric
            score_result = metric.score(**result)
            return (
                score_result.value if hasattr(score_result, "value") else score_result
            )
        except Exception as e:
            print(f"Error calculating metric score: {e}")
            return 0.0

    return opik_metric_score_wrapper


def create_dspy_training_set(
    data: list[dict], input: str, n_samples: Optional[int] = None
) -> list[dspy.Example]:
    """
    Turn a list of dicts into a list of dspy Examples
    """
    output = []

    if n_samples is not None:
        data = random.sample(data, n_samples)

    for example in data:
        example_obj = dspy.Example(
            **example, dspy_uuid=str(uuid.uuid4()), dspy_split="train"
        )
        example_obj = example_obj.with_inputs(input)
        output.append(example_obj)
    return output


def get_tool_prompts(tool_names, text: str) -> Dict[str, str]:
    """
    Extract the embedded tool prompts from a text.
    """
    tool_prompts = {}
    for count, tool_name in enumerate(tool_names):
        pattern = rf"\b{tool_name}\b[, \.]*([^{count + 2}]*)"
        match = re.search(pattern, text)
        if match:
            description = match.groups()[0]
            if description:
                tool_prompts[tool_name] = description.strip()
    return tool_prompts
