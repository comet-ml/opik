import logging
from typing import Any, cast, Dict, List, Optional, Type, TYPE_CHECKING
import pydantic

from . import opik_monitoring, message_converters, response_parser
from ...models import base_model

if TYPE_CHECKING:
    import langchain_core.language_models
    import langchain_core.messages

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
        message = self.generate_chat_completion(
            messages=[{"role": "user", "content": input}],
            response_format=response_format,
            **kwargs,
        )
        return message["content"]

    def generate_chat_completion(
        self,
        messages: List[base_model.ConversationDict],
        response_format: Optional[Type[pydantic.BaseModel]] = None,
        **kwargs: Any,
    ) -> base_model.ConversationDict:
        """
        Generate the assistant turn from a list of chat messages.

        Args:
            messages: A list of ``{"role": ..., "content": ...}`` dictionaries.
            response_format: Optional Pydantic model specifying the expected output format.
            kwargs: Additional arguments forwarded to the langchain chat model's invoke call.

        Returns:
            ``{"role": "assistant", "content": ...}``.
        """
        if response_format is not None:
            kwargs["response_format"] = response_format

        with base_model.get_provider_response(
            model_provider=self,
            messages=cast(List[Dict[str, Any]], list(messages)),
            **kwargs,
        ) as response:
            return response_parser.parse_assistant_message(response)

    def generate_provider_response(
        self,
        messages: List[Dict[str, Any]],
        **kwargs: Any,
    ) -> "langchain_core.messages.AIMessage":
        """
        Do not use this method directly. It is intended to be used within `base_model.get_provider_response()` method.

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
        message = await self.agenerate_chat_completion(
            messages=[{"role": "user", "content": input}],
            response_format=response_format,
            **kwargs,
        )
        return message["content"]

    async def agenerate_chat_completion(
        self,
        messages: List[base_model.ConversationDict],
        response_format: Optional[Type[pydantic.BaseModel]] = None,
        **kwargs: Any,
    ) -> base_model.ConversationDict:
        """
        Async counterpart of :meth:`generate_chat_completion`.
        """
        if response_format is not None:
            kwargs["response_format"] = response_format

        async with base_model.aget_provider_response(
            model_provider=self,
            messages=cast(List[Dict[str, Any]], list(messages)),
            **kwargs,
        ) as response:
            return response_parser.parse_assistant_message(response)

    async def agenerate_provider_response(
        self, messages: List[Dict[str, Any]], **kwargs: Any
    ) -> "langchain_core.messages.AIMessage":
        """
        Do not use this method directly. It is intended to be used within `base_model.aget_provider_response()` method.

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
