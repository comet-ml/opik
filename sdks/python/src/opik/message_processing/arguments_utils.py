from typing import Any, Optional, Set


def recursive_shallow_copy(obj: Any, seen: Optional[Set[int]] = None) -> Any:
    """
    Creates a recursive shallow copy of a dictionary or list which may include other dictionaries
    or lists as values.

    Args:
        obj: The object to copy. Can be a dict, list, or any other type.
        seen: Ids of the containers on the current copy path, used to detect cycles.

    Returns:
        A recursive shallow copy of the input object where dictionaries and lists are copied,
        but their non-container elements are referenced. A container that contains itself
        is replaced with a cyclic reference marker string.
    """
    if not isinstance(obj, (dict, list)):
        # For non-container types, return the object itself (shallow copy)
        return obj

    if seen is None:
        seen = set()

    obj_id = id(obj)
    if obj_id in seen:
        # Same format as the cyclic reference marker in jsonable_encoder
        return f"<Cyclic reference to {type(obj).__name__} id={obj_id}>"

    seen.add(obj_id)
    try:
        if isinstance(obj, dict):
            return {k: recursive_shallow_copy(v, seen) for k, v in obj.items()}
        return [recursive_shallow_copy(item, seen) for item in obj]
    finally:
        seen.discard(obj_id)
