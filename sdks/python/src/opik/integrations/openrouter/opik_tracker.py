from typing import (
    Any,
    Callable,
    Dict,
    List,
    Optional,
    Tuple,
    TypeVar,
)

import opik.dict_utils as dict_utils
import opik.llm_usage as llm_usage
from opik.api_objects import span
from opik.decorator import arguments_helpers, base_track_decorator
from opik.types import LLMProvider
from typing_extensions import override

import logging


LOGGER = logging.getLogger(__name__)


OpenRouterClient = TypeVar("OpenRouterClient", bound=Any)

KWARGS_KEYS_TO_LOG_AS_INPUTS = [
    "messages",
    "max_tokens",
    "temperature",
    "top_p",
    "stream",
]
RESPONSE_KEYS_TO_LOG_AS_OUTPUT = ["choices"]


def track_openrouter(
    openrouter_client: OpenRouterClient,
    project_name: Optional[str] = None,
) -> OpenRouterClient:
    """Adds Opik tracking to an OpenRouter client.

    This integration tracks the official OpenRouter Python SDK's chat API methods:
    * `client.chat.send()`
    * `client.chat.send_async()`

    For non-streaming and streaming calls, OpenRouter's input and output are
    captured as generic OpenAI-compatible payloads.

    Args:
        openrouter_client: An instance of OpenRouter client.
        project_name: The name of the project to log data.

    Returns:
        OpenRouter client with Opik tracking enabled.
    """
    if hasattr(openrouter_client, "opik_tracked"):
        return openrouter_client

    if not hasattr(openrouter_client, "chat") or not hasattr(
        openrouter_client.chat, "send"
    ):
        LOGGER.warning(
            "Skipping OpenRouter tracking: expected `chat.send` method on client"
        )
        return openrouter_client

    openrouter_client.opik_tracked = True

    decorator_factory = _OpenrouterChatTrackDecorator()
    chat_send_decorator = decorator_factory.track(
        type="llm",
        name="openrouter_chat_send",
        project_name=project_name,
    )
    chat_send_async_decorator = decorator_factory.track(
        type="llm",
        name="openrouter_chat_send_async",
        project_name=project_name,
    )

    openrouter_client.chat.send = chat_send_decorator(openrouter_client.chat.send)
    if hasattr(openrouter_client.chat, "send_async"):
        openrouter_client.chat.send_async = chat_send_async_decorator(
            openrouter_client.chat.send_async
        )

    return openrouter_client


class _OpenrouterChatTrackDecorator(base_track_decorator.BaseTrackDecorator):
    @override
    def __init__(self) -> None:
        super().__init__()
        self.provider = "openrouter"

    @override
    def _start_span_inputs_preprocessor(
        self,
        func: Callable,
        track_options: arguments_helpers.TrackOptions,
        args: Tuple,
        kwargs: Dict[str, Any],
    ) -> arguments_helpers.StartSpanParameters:
        name = track_options.name if track_options.name is not None else func.__name__
        metadata = track_options.metadata if track_options.metadata is not None else {}

        input_data, new_metadata = dict_utils.split_dict_by_keys(
            kwargs, keys=KWARGS_KEYS_TO_LOG_AS_INPUTS
        )
        metadata = dict_utils.deepmerge(metadata, new_metadata)
        metadata.update(
            {
                "created_from": "openrouter",
                "type": "openrouter_chat",
            }
        )

        tags = ["openrouter"]
        model = kwargs.get("model", None)

        return arguments_helpers.StartSpanParameters(
            name=name,
            input=input_data,
            type=track_options.type,
            tags=tags,
            metadata=metadata,
            project_name=track_options.project_name,
            model=model,
            provider=self.provider,
        )

    @override
    def _end_span_inputs_preprocessor(
        self,
        output: Any,
        capture_output: bool,
        current_span_data: span.SpanData,
    ) -> arguments_helpers.EndSpanParameters:
        result_dict = _convert_response_to_dict(output)
        output_data, metadata = dict_utils.split_dict_by_keys(
            result_dict, keys=RESPONSE_KEYS_TO_LOG_AS_OUTPUT
        )

        usage = result_dict.get("usage")
        opik_usage = None
        if usage is not None:
            opik_usage = llm_usage.try_build_opik_usage_or_log_error(
                provider=LLMProvider.OPENAI,
                usage=usage,
                logger=LOGGER,
                error_message="Failed to log token usage from OpenRouter chat call",
            )

            if opik_usage is None:
                opik_usage = llm_usage.build_opik_usage_from_unknown_provider(usage=usage)

        return arguments_helpers.EndSpanParameters(
            output=output_data,
            usage=opik_usage,
            metadata=metadata,
            model=result_dict.get("model", None),
            provider=self.provider,
        )

    @override
    def _streams_handler(
        self,
        output: Any,
        capture_output: bool,
        generations_aggregator: Optional[Callable[[List[Any]], Any]],
    ) -> Optional[Any]:
        return None



def _convert_response_to_dict(response: Any) -> Dict[str, Any]:
    if isinstance(response, dict):
        return response

    if hasattr(response, "model_dump"):
        return response.model_dump(mode="json")

    if hasattr(response, "dict"):
        try:
            return response.dict()
        except Exception:
            return {"response": str(response)}

    return {"response": str(response)}
