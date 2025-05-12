import abc
from typing import Any, List, Dict


class OpikBaseModel(abc.ABC):
    """
    This class serves as an interface to LLMs.

    If you want to implement custom LLM provider in evaluation metrics,
    you should inherit from this class.
    """

    def __init__(self, model_name: str):
        """
        Initializes the base model with a given model name.

        Args:
            model_name: The name of the LLM to be used.
        """
        self.model_name = model_name

    @abc.abstractmethod
    def generate_string(self, input: str, **kwargs: Any) -> str:
        """
        Simplified interface to generate a string output from the model.

        Args:
            input: The input string based on which the model will generate the output.
            kwargs: Additional arguments that may be used by the model for string generation.

        Returns:
            str: The generated string output.
        """
        pass

    @abc.abstractmethod
    def generate_provider_response(
        self, messages: List[Dict[str, Any]], **kwargs: Any
    ) -> Any:
        """
        Generate a provider-specific response. Can be used to interface with
        the underlying model provider (e.g., OpenAI, Anthropic) and get raw output.

        Args:
            messages: A list of messages to be sent to the model, should be a list of dictionaries with the keys
            kwargs: arguments required by the provider to generate a response.

        Returns:
            Any: The response from the model provider, which can be of any type depending on the use case and LLM.
        """
        pass

    async def agenerate_string(self, input: str, **kwargs: Any) -> str:
        """
        Simplified interface to generate a string output from the model. Async version.

        Args:
            input: The input string based on which the model will generate the output.
            kwargs: Additional arguments that may be used by the model for string generation.

        Returns:
            str: The generated string output.
        """
        raise NotImplementedError("Async generation not implemented for this provider")

    async def agenerate_provider_response(
        self, messages: List[Dict[str, Any]], **kwargs: Any
    ) -> Any:
        """
        Generate a provider-specific response. Can be used to interface with
        the underlying model provider (e.g., OpenAI, Anthropic) and get raw output.
        Async version.

        Args:
            messages: A list of messages to be sent to the model, should be a list of dictionaries with the keys
                "content" and "role".
            kwargs: arguments required by the provider to generate a response.

        Returns:
            Any: The response from the model provider, which can be of any type depending on the use case and LLM.
        """
        raise NotImplementedError("Async generation not implemented for this provider")
