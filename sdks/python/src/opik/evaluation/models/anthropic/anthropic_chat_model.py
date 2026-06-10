import importlib.util
import logging
from typing import Any, cast, Dict, List, Optional, Type

import pydantic
import tenacity

import opik.config as opik_config
from .. import base_model
from opik import exceptions
from . import message_adapter, response_parser

LOGGER = logging.getLogger(__name__)

DEFAULT_MAX_TOKENS = 4096


def is_available() -> bool:
    return importlib.util.find_spec("anthropic") is not None


class AnthropicChatModel(base_model.OpikBaseModel):
    def __init__(
        self,
        model_name: str = "anthropic/claude-sonnet-4-20250514",
        track: bool = True,
        **completion_kwargs: Any,
    ) -> None:
        """
        Initializes the Anthropic model using the native anthropic Python SDK.

        Args:
            model_name: The name of the model. Use "anthropic/" prefix or bare model name.
            track: Whether to track the model calls via Opik tracing.
            completion_kwargs: Additional arguments always passed to messages.create().
        """
        import anthropic

        super().__init__(model_name=model_name)

        self._api_model_name = message_adapter.strip_anthropic_prefix(model_name)
        self._unsupported_warned: set[str] = set()
        self._completion_kwargs = message_adapter.filter_unsupported_params(
            completion_kwargs, self._unsupported_warned
        )
        if "tool_choice" in self._completion_kwargs:
            self._completion_kwargs["tool_choice"] = (
                message_adapter.normalize_tool_choice(
                    self._completion_kwargs["tool_choice"]
                )
            )
        # The same shape mismatch can come in at construction time
        # (`AnthropicChatModel(tools=[...])` or via the factory). The
        # per-call path normalizes `filtered_extra["tools"]` in
        # `_build_call_kwargs`, but `self._completion_kwargs["tools"]`
        # gets merged in alongside it without that translation —
        # without normalizing here, OpenAI-shape specs passed at init
        # would still reach the Anthropic SDK unchanged.
        if "tools" in self._completion_kwargs:
            self._completion_kwargs["tools"] = message_adapter.normalize_tools(
                self._completion_kwargs["tools"]
            )

        config = opik_config.OpikConfig()
        enable_tracking = track and config.enable_litellm_models_monitoring

        client = anthropic.Anthropic(timeout=60.0)
        async_client = anthropic.AsyncAnthropic(timeout=60.0)

        if enable_tracking:
            from opik.integrations.anthropic import track_anthropic

            client = track_anthropic(client)
            async_client = track_anthropic(async_client)

        self._client = client
        self._async_client = async_client

    def generate_string(
        self,
        input: str,
        response_format: Optional[Type[pydantic.BaseModel]] = None,
        **kwargs: Any,
    ) -> str:
        message = self.generate_chat_completion(
            messages=[{"role": "user", "content": input}],
            response_format=response_format,
            **kwargs,
        )
        return message["content"]

    def _effective_tool_names(self, call_kwargs: Dict[str, Any]) -> List[str]:
        """Names of every tool registered for this call.

        Mirrors the precedence of `_build_call_kwargs` (`{**self._completion_kwargs, **filtered_extra}`):
        per-call `tools` override constructor-time `tools` rather than
        merge. Returned names go to the response parser, so a real tool
        call (`read` / `scan` / `search`) doesn't get misclassified as
        the structured-output finalizer when both arrive as `tool_use`
        blocks. Used by both the sync and async paths.
        """
        effective_tools = call_kwargs.get("tools", self._completion_kwargs.get("tools"))
        return message_adapter.extract_tool_names(effective_tools)

    def generate_chat_completion(
        self,
        messages: List[base_model.ConversationDict],
        response_format: Optional[Type[pydantic.BaseModel]] = None,
        **kwargs: Any,
    ) -> base_model.ConversationDict:
        if response_format is not None:
            kwargs["response_format"] = response_format

        registered_tool_names = self._effective_tool_names(kwargs)

        with base_model.get_provider_response(
            model_provider=self,
            messages=cast(List[Dict[str, Any]], list(messages)),
            **kwargs,
        ) as response:
            return response_parser.parse_assistant_message(
                response, registered_tool_names=registered_tool_names
            )

    def generate_provider_response(
        self,
        messages: List[Dict[str, Any]],
        **kwargs: Any,
    ) -> Any:
        retries = kwargs.pop("__opik_retries", 3)
        try:
            max_attempts = max(1, int(retries))
        except (TypeError, ValueError):
            max_attempts = 1

        response_format = kwargs.pop("response_format", None)
        call_kwargs = self._build_call_kwargs(messages, kwargs)

        retrying = tenacity.Retrying(
            reraise=True,
            stop=tenacity.stop_after_attempt(max_attempts),
            wait=tenacity.wait_exponential(multiplier=0.5, min=0.5, max=8.0),
        )

        if response_format is not None:
            call_kwargs["output_format"] = response_format
            return retrying(self._client.messages.parse, **call_kwargs)

        return retrying(self._client.messages.create, **call_kwargs)

    async def agenerate_string(
        self,
        input: str,
        response_format: Optional[Type[pydantic.BaseModel]] = None,
        **kwargs: Any,
    ) -> str:
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
        if response_format is not None:
            kwargs["response_format"] = response_format

        registered_tool_names = self._effective_tool_names(kwargs)

        async with base_model.aget_provider_response(
            model_provider=self,
            messages=cast(List[Dict[str, Any]], list(messages)),
            **kwargs,
        ) as response:
            return response_parser.parse_assistant_message(
                response, registered_tool_names=registered_tool_names
            )

    async def agenerate_provider_response(
        self,
        messages: List[Dict[str, Any]],
        **kwargs: Any,
    ) -> Any:
        retries = kwargs.pop("__opik_retries", 3)
        try:
            max_attempts = max(1, int(retries))
        except (TypeError, ValueError):
            max_attempts = 1

        response_format = kwargs.pop("response_format", None)
        call_kwargs = self._build_call_kwargs(messages, kwargs)

        retrying = tenacity.AsyncRetrying(
            reraise=True,
            stop=tenacity.stop_after_attempt(max_attempts),
            wait=tenacity.wait_exponential(multiplier=0.5, min=0.5, max=8.0),
        )

        if response_format is not None:
            call_kwargs["output_format"] = response_format
            async for attempt in retrying:
                with attempt:
                    return await self._async_client.messages.parse(**call_kwargs)
        else:
            async for attempt in retrying:
                with attempt:
                    return await self._async_client.messages.create(**call_kwargs)

        raise exceptions.BaseLLMError(
            "Async Anthropic completion failed without raising an exception"
        )

    def _build_call_kwargs(
        self,
        messages: List[Dict[str, Any]],
        extra_kwargs: Dict[str, Any],
    ) -> Dict[str, Any]:
        system_text, non_system_messages = message_adapter.extract_system_messages(
            messages
        )
        # Translate OpenAI-shape `role: "tool"` follow-ups and
        # assistant `tool_calls` into Anthropic's content-block form
        # before they hit the API — see `normalize_messages` for the
        # full mapping.
        non_system_messages = message_adapter.normalize_messages(non_system_messages)

        filtered_extra = message_adapter.filter_unsupported_params(
            extra_kwargs, self._unsupported_warned
        )
        # OpenAI-style `tool_choice` ("auto" / "none" / "required" /
        # `{"type": "function", ...}`) needs to be translated to the
        # Anthropic object form — the agentic loop emits the OpenAI
        # shape because that's the cross-provider convention.
        if "tool_choice" in filtered_extra:
            filtered_extra["tool_choice"] = message_adapter.normalize_tool_choice(
                filtered_extra["tool_choice"]
            )
        # Same provider mismatch on `tools`: OpenAI wraps each spec in
        # `{type: "function", function: {...}}`; Anthropic's newer
        # schema requires `{type: "custom", name, description,
        # input_schema}` and rejects the `function` discriminator.
        if "tools" in filtered_extra:
            filtered_extra["tools"] = message_adapter.normalize_tools(
                filtered_extra["tools"]
            )

        call_kwargs: Dict[str, Any] = {
            **self._completion_kwargs,
            **filtered_extra,
        }
        call_kwargs["model"] = self._api_model_name
        call_kwargs["messages"] = non_system_messages
        call_kwargs.setdefault("max_tokens", DEFAULT_MAX_TOKENS)

        if system_text:
            call_kwargs["system"] = system_text

        return call_kwargs
