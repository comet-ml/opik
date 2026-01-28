from typing import Any

import pydantic

from opik import dict_utils


def merge_tags(
    existing_tags: list[str] | None, new_tags: list[str] | None
) -> list[str] | None:
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
    existing_metadata: dict[str, Any] | None,
    new_metadata: dict[str, Any] | pydantic.BaseModel | None,
    prompts: list[dict[str, Any]] | None = None,
) -> dict[str, Any] | None:
    """Merge the existing metadata dictionary with new data, with new values taking precedence.

    If both existing_metadata and new_metadata are None or empty, return None.
    """
    if prompts is not None:
        new_metadata = new_metadata or {}
        new_metadata["opik_prompts"] = prompts

    return _merge_dictionary_with_data(existing_metadata, new_data=new_metadata)


def merge_inputs(
    existing_inputs: dict[str, Any] | None,
    new_inputs: dict[str, Any] | pydantic.BaseModel | None,
) -> dict[str, Any] | None:
    """Merge the existing input dictionary with new data, with new values taking precedence.

    If both existing_inputs and new_inputs are None or empty, return None."""
    return _merge_dictionary_with_data(existing_inputs, new_data=new_inputs)


def merge_outputs(
    existing_outputs: dict[str, Any] | None,
    new_outputs: dict[str, Any] | pydantic.BaseModel | None,
) -> dict[str, Any] | None:
    """Merge the existing output dictionary with new data, with new values taking precedence.

    If both existing_outputs and new_outputs are None or empty, return None."""
    return _merge_dictionary_with_data(existing_outputs, new_data=new_outputs)


def _merge_dictionary_with_data(
    existing_dict: dict[str, Any] | None,
    new_data: dict[str, Any] | pydantic.BaseModel | None,
) -> dict[str, Any] | None:
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
