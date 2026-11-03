from typing import Optional, Any, Dict, List, Union

import pydantic

from opik import dict_utils


def _coerce_to_dict_or_raise(
    value: Optional[Union[Dict[str, Any], pydantic.BaseModel]],
    *,
    name: str,
) -> Optional[Dict[str, Any]]:
    """Return `value` as a plain dict, or raise a named error.

    Every one of these helpers ends up in dict_utils.deepmerge, which assumes a
    mapping: a caller passing a JSON string instead of a dict used to get an
    AttributeError from deep inside the merge, far from the argument that
    caused it.
    """
    if isinstance(value, pydantic.BaseModel):
        return value.model_dump()
    if value is not None and not isinstance(value, dict):
        raise ValueError(
            f"{name} must be a dict or a pydantic BaseModel, got {type(value).__name__}"
        )
    return value


def merge_tags(
    existing_tags: Optional[List[str]], new_tags: Optional[List[str]]
) -> Optional[List[str]]:
    """Merge tag lists, preserving existing tags and adding new ones.

    If both existing_tags and new_tags are None or empty, return None."""
    if existing_tags is None and new_tags is None:
        return None

    result = list(existing_tags or [])
    if new_tags:
        for tag in new_tags:
            if tag not in result:
                result.append(tag)

    return result if result else None


def merge_metadata(
    existing_metadata: Optional[Dict[str, Any]],
    new_metadata: Optional[Union[Dict[str, Any], pydantic.BaseModel]],
    prompts: Optional[List[Dict[str, Any]]] = None,
) -> Optional[Dict[str, Any]]:
    """Merge the existing metadata dictionary with new data, with new values taking precedence.

    If both existing_metadata and new_metadata are None or empty, return None.
    """
    new_metadata = _coerce_to_dict_or_raise(new_metadata, name="metadata")

    if prompts is not None:
        new_metadata = new_metadata or {}
        new_metadata["opik_prompts"] = prompts

    return _merge_dictionary_with_data(existing_metadata, new_data=new_metadata)


def merge_inputs(
    existing_inputs: Optional[Dict[str, Any]],
    new_inputs: Optional[Union[Dict[str, Any], pydantic.BaseModel]],
) -> Optional[Dict[str, Any]]:
    """Merge the existing input dictionary with new data, with new values taking precedence.

    If both existing_inputs and new_inputs are None or empty, return None."""
    new_inputs = _coerce_to_dict_or_raise(new_inputs, name="inputs")
    return _merge_dictionary_with_data(existing_inputs, new_data=new_inputs)


def merge_outputs(
    existing_outputs: Optional[Dict[str, Any]],
    new_outputs: Optional[Union[Dict[str, Any], pydantic.BaseModel]],
) -> Optional[Dict[str, Any]]:
    """Merge the existing output dictionary with new data, with new values taking precedence.

    If both existing_outputs and new_outputs are None or empty, return None."""
    new_outputs = _coerce_to_dict_or_raise(new_outputs, name="outputs")
    return _merge_dictionary_with_data(existing_outputs, new_data=new_outputs)


def _merge_dictionary_with_data(
    existing_dict: Optional[Dict[str, Any]],
    new_data: Optional[Union[Dict[str, Any], pydantic.BaseModel]],
) -> Optional[Dict[str, Any]]:
    """Merge the dictionary with new data, with new values taking precedence.

    If both existing_dict and new_data are None or empty, return None."""
    if existing_dict is None and new_data is None:
        return None

    if isinstance(new_data, pydantic.BaseModel):
        new_data = new_data.model_dump()

    result = dict(existing_dict or {})
    if new_data:
        result = dict_utils.deepmerge(result, new_data)

    return result if result else None
