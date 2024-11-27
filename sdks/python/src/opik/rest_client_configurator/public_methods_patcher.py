from typing import Any, Callable


def patch(instance: Any, decorator: Callable[[Callable], Callable]) -> None:
    attr_name: str
    for attr_name in dir(instance):
        attr_value = getattr(instance, attr_name)
        if callable(attr_value) and not attr_name.startswith("_"):
            decorated_method = decorator(attr_value)
            setattr(instance, attr_name, decorated_method)
