import logging
from typing import Any, Optional, Type, List, Dict, TYPE_CHECKING
import pydantic

from . import opik_monitoring, message_converters
from ...models import base_model

if TYPE_CHECKING:
    import langchain_core.language_models
    from langchain import schema

LOGGER = logging.getLogger(__name__)


class LangchainChatModel(base_model.OpikBaseModel):
    def __init__(
        self,
        chat_model: "langchain_core.language_models.BaseChatModel",
        track: bool = True,
    ) -> None:
        """
        Initializes the model with a given Langchain chat model instance.

        Args:
            chat_model: A Langchain chat model instance to wrap.
                It is assumed that the BaseChatModel is already configured and
                all the requirement dependencies are installed.
            track: Whether to track the model calls.
        """
        model_name = _extract_model_name(chat_model)
        super().__init__(model_name=model_name)

        self._engine = chat_model
        self._track = track

    def generate_string(
        self,
        input: str,
        response_format: Optional[Type[pydantic.BaseModel]] = None,
        **kwargs: Any,
    ) -> str:
        """
        Simplified interface to generate a string output from the model.

        Args:
            input: The input string based on which the model will generate the output.
            response_format: pydantic model specifying the expected output string format.
            kwargs: Additional arguments that may be used by the model for string generation.

        Returns:
            str: The generated string output.
        """
        if response_format is not None:
            kwargs["response_format"] = response_format

        request = [
            {
                "content": input,
                "role": "user",
            },
        ]
        response = self.generate_provider_response(messages=request, **kwargs)
        return response.content

    def generate_provider_response(
        self,
        messages: List[Dict[str, Any]],
        **kwargs: Any,
    ) -> "schema.AIMessage":
        """
        Generate a provider-specific response using the Langchain model.

        Args:
            messages: A list of messages to be sent to the model, should be a list of dictionaries with the keys
                "content" and "role".
            kwargs: arguments required by the provider to generate a response.

        Returns:
            ModelResponse: The response from the model provider.
        """
        langchain_messages = message_converters.convert_to_langchain_messages(messages)

        opik_monitoring.add_opik_tracer_to_params(kwargs)
        response = self._engine.invoke(langchain_messages, **kwargs)

        return response

    async def agenerate_string(
        self,
        input: str,
        response_format: Optional[Type[pydantic.BaseModel]] = None,
        **kwargs: Any,
    ) -> str:
        """
        Simplified interface to generate a string output from the model. Async version.

        Args:
            input: The input string based on which the model will generate the output.
            response_format: pydantic model specifying the expected output string format.
            kwargs: Additional arguments that may be used by the model for string generation.

        Returns:
            str: The generated string output.
        """
        if response_format is not None:
            kwargs["response_format"] = response_format

        request = [
            {
                "content": input,
                "role": "user",
            },
        ]

        response = await self.agenerate_provider_response(messages=request, **kwargs)
        return response.content

    async def agenerate_provider_response(
        self, messages: List[Dict[str, Any]], **kwargs: Any
    ) -> "schema.AIMessage":
        """
        Generate a provider-specific response using the Langchain model. Async version.

        Args:
            messages: A list of messages to be sent to the model, should be a list of dictionaries with the keys
                "content" and "role".
            kwargs: arguments required by the provider to generate a response.

        Returns:
            ModelResponse: The response from the model provider.
        """
        langchain_messages = message_converters.convert_to_langchain_messages(messages)

        opik_monitoring.add_opik_tracer_to_params(kwargs)
        response = await self._engine.ainvoke(langchain_messages, **kwargs)

        return response


def _extract_model_name(
    langchain_chat_model: "langchain_core.language_models.BaseChatModel",
) -> str:
    if hasattr(langchain_chat_model, "model") and isinstance(
        langchain_chat_model.model, str
    ):
        return langchain_chat_model.model

    if hasattr(langchain_chat_model, "model_name") and isinstance(
        langchain_chat_model.model_name, str
    ):
        return langchain_chat_model.model_name

    if hasattr(langchain_chat_model, "model_id") and isinstance(
        langchain_chat_model.model_id, str
    ):
        return langchain_chat_model.model_id

    return "unknown-model"
