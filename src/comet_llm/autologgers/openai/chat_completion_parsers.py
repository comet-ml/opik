from typing import Dict, Any, Tuple

Inputs = Outputs = Metadata = Dict[str, Any]

def parse_create_arguments(kwargs: Dict[str, Any]) -> Tuple[Inputs, Metadata]:
    kwargs_copy = kwargs.copy()
    inputs = {}

    inputs["messages"] = kwargs_copy.pop("messages")
    if "function_call" in kwargs_copy:
        inputs["function_call"] = kwargs_copy.pop("function_call")

    metadata = {
        "created_from": "openai",
        "type": "openai_chat",
        **kwargs_copy
    }

    return inputs, metadata


def parse_create_result(result: Any) -> Tuple[Outputs, Metadata]:
    choices = [choice['message'].to_dict() for choice in result['choices']]
    outputs = {"choices": choices}
    metadata = {"usage": result['usage'].to_dict()}

    return outputs, metadata