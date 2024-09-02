import abc


class OpikBaseModel(abc.ABC):
    """
    This class serves as an interface to LLMs.

    By the moment it's updated last time, str -> str relation
    for generate and agenerate methods is enough, but it may
    evolve in future if it becomes clear that we need models
    not just consuming or returning strings only.
    """

    def __init__(self, model_name: str):
        self.model_name = model_name

    @abc.abstractmethod
    def generate(self, input: str) -> str: ...

    @abc.abstractmethod
    async def agenerate(self, input: str) -> str: ...
