from typing import Optional, Any, Dict, List

from opik import dict_utils


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
    existing_metadata: Optional[Dict[str, Any]], new_metadata: Optional[Dict[str, Any]]
) -> Optional[Dict[str, Any]]:
    """Merge metadata dictionaries, with new values taking precedence.

    If both existing_metadata and new_metadata are None or empty, return None.
    """
    return _merge_dictionaries(existing_metadata, new_dict=new_metadata)


def merge_inputs(
    existing_inputs: Optional[Dict[str, Any]], new_inputs: Optional[Dict[str, Any]]
) -> Optional[Dict[str, Any]]:
    """Merge input dictionaries, with new values taking precedence.

    If both existing_inputs and new_inputs are None or empty, return None."""
    return _merge_dictionaries(existing_inputs, new_dict=new_inputs)


def merge_outputs(
    existing_outputs: Optional[Dict[str, Any]], new_outputs: Optional[Dict[str, Any]]
) -> Optional[Dict[str, Any]]:
    """Merge output dictionaries, with new values taking precedence.

    If both existing_outputs and new_outputs are None or empty, return None."""
    return _merge_dictionaries(existing_outputs, new_dict=new_outputs)


def _merge_dictionaries(
    existing_dict: Optional[Dict[str, Any]], new_dict: Optional[Dict[str, Any]]
) -> Optional[Dict[str, Any]]:
    """Merge two dictionaries, with new values taking precedence.

    If both existing_dict and new_dict are None or empty, return None."""
    if existing_dict is None and new_dict is None:
        return None

    result = dict(existing_dict or {})
    if new_dict:
        result = dict_utils.deepmerge(result, new_dict)

    return result if result else None
