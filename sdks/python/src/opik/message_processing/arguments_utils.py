from typing import Any, Optional, Set


def recursive_shallow_copy(obj: Any, _seen_ids: Optional[Set[int]] = None) -> Any:
    """
    Creates a recursive shallow copy of a dictionary or list which may include other dictionaries
    or lists as values.

    Containers already on the active copy path are replaced with a cycle
    marker instead of being descended into again, so a self-referencing
    input (e.g. ``d["self"] = d``) cannot run into the recursion limit.
    The ids are removed again on the way out, so the same container may
    still be copied again at a sibling branch -- only a genuine cycle on
    the active path is marked.

    The marker carries no object id on purpose: span payloads are stored
    and compared across runs, so the marker must be deterministic (unlike
    the debug marker in ``jsonable_encoder``).

    Args:
        obj: The object to copy. Can be a dict, list, or any other type.

    Returns:
        A recursive shallow copy of the input object where dictionaries and lists are copied,
        but their non-container elements are referenced.
    """
    if isinstance(obj, dict):
        if _seen_ids is None:
            _seen_ids = set()
        obj_id = id(obj)
        if obj_id in _seen_ids:
            return "<Cyclic reference to dict>"
        _seen_ids.add(obj_id)
        try:
            return {k: recursive_shallow_copy(v, _seen_ids) for k, v in obj.items()}
        finally:
            _seen_ids.discard(obj_id)
    elif isinstance(obj, list):
        if _seen_ids is None:
            _seen_ids = set()
        obj_id = id(obj)
        if obj_id in _seen_ids:
            return "<Cyclic reference to list>"
        _seen_ids.add(obj_id)
        try:
            return [recursive_shallow_copy(item, _seen_ids) for item in obj]
        finally:
            _seen_ids.discard(obj_id)
    else:
        # For non-container types, return the object itself (shallow copy)
        return obj
