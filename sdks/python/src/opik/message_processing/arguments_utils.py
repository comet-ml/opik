from typing import Any


def recursive_shallow_copy(obj: Any) -> Any:
    """
    Creates a recursive shallow copy of a dictionary or list which may include other dictionaries
    or lists as values.

    Args:
        obj: The object to copy. Can be a dict, list, or any other type.

    Returns:
        A recursive shallow copy of the input object where dictionaries and lists are copied,
        but their non-container elements are referenced.
    """
    if isinstance(obj, dict):
        return {k: recursive_shallow_copy(v) for k, v in obj.items()}
    elif isinstance(obj, list):
        return [recursive_shallow_copy(item) for item in obj]
    else:
        # For non-container types, return the object itself (shallow copy)
        return obj
