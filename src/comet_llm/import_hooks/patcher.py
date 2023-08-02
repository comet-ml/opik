import inspect
from types import ModuleType
from typing import TYPE_CHECKING, Any

from . import wrapper

if TYPE_CHECKING:
    from . import module_extension

# _get_object and _set_object copied from comet_ml.monkeypatching almost without any changes.


def _get_object(module: ModuleType, object_name: str) -> Any:
    object_path = object_name.split(".")
    current_object = module

    for part in object_path:
        try:
            current_object = getattr(current_object, part)
        except AttributeError:
            return None

    return current_object


def _set_object(
    module: ModuleType, object_name: str, original: Any, new_object: Any
) -> None:
    object_path = object_name.split(".")
    object_to_patch = _get_object(module, object_path[:-1])

    original_self = getattr(original, "__self__", None)

    # Support classmethod
    if original_self and inspect.isclass(original_self):
        new_object = classmethod(new_object)

    setattr(object_to_patch, object_path[-1], new_object)


def patch(module: ModuleType, module_extension: "module_extension.ModuleExtension"):
    for callable_name, callable_extenders in module_extension.items():
        original = _get_object(module, callable_name)

        if original is None:
            continue

        new_callable = wrapper.wrap(original, callable_extenders)
        _set_object(module, callable_name, original, new_callable)

    return module
