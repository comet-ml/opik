from typing import Any, Callable, Dict, List

from . import callable_extenders, module_extension


class Registry:
    def __init__(self):
        self._modules_extensions: Dict[str, module_extension.ModuleExtension] = {}

    @property
    def module_names(self) -> List[str]:
        return self._modules_extensions.keys()

    def get_extension(self, module_name: str) -> module_extension.ModuleExtension:
        return self._modules_extensions[module_name]

    def _get_callable_extenders(
        self, module_name: str, callable_name: str
    ) -> callable_extenders.CallableExtenders:
        extension = self._modules_extensions.setdefault(
            module_name, module_extension.ModuleExtension()
        )
        return extension.extenders(callable_name)

    def register_before(
        self, module_name: str, callable_name: str, patcher_function: Callable
    ) -> None:
        extenders = self._get_callable_extenders(module_name, callable_name)
        extenders.before.append(patcher_function)

    def register_after(
        self, module_name: str, callable_name: str, patcher_function: Callable
    ) -> None:
        extenders = self._get_callable_extenders(module_name, callable_name)
        extenders.after.append(patcher_function)

    def register_after_exception(
        self, module_name: str, callable_name: str, patcher_function: Callable
    ) -> None:
        extenders = self._get_callable_extenders(module_name, callable_name)
        extenders.after_exception.append(patcher_function)
