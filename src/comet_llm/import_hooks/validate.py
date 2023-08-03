from typing import Any

def args_kwargs(obj: Any) -> bool:
    if obj is None:
        return False

    try:
        args, kwargs = obj
    except (ValueError, TypeError):
        return False

    if not isinstance(args, (list, tuple)):
        return False

    if not isinstance(kwargs, dict):
        return False

    return True
