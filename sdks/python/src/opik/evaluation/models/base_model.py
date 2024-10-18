import abc
from typing import Any


class OpikBaseModel(abc.ABC):
    """
    This class serves as an interface to LLMs.
    """

    def __init__(self, model_name: str):
        self.model_name = model_name

    @abc.abstractmethod
    def generate(self, input: Any, **kwargs: Any) -> Any: ...

    @abc.abstractmethod
    async def agenerate(self, input: Any, **kwargs: Any) -> Any: ...
