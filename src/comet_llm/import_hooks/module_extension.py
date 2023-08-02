from typing import Any, Dict, List

from . import callable_extenders


class ModuleExtension:
    def __init__(self) -> None:
        self._callable_names_extenders: Dict[
            str, callable_extenders.CallableExtenders
        ] = {}

    def extenders(self, callable_name: str) -> callable_extenders.CallableExtenders:
        if callable_name not in self._callable_names_extenders:
            self._callable_names_extenders[callable_name] = callable_extenders.get()

        return self._callable_names_extenders[callable_name]

    def callable_names(self) -> List[str]:
        return self._callable_names_extenders.keys()

    def items(self):
        return self._callable_names_extenders.items()
