import logging
from typing import (
    Any,
    AsyncGenerator,
    Callable,
    Dict,
    Generator,
    List,
    Optional,
    Tuple,
    Union,
)
from typing_extensions import override

import aisuite.framework as aisuite_chat_completion
from openai.types.chat import chat_completion as openai_chat_completion

from opik import dict_utils, llm_usage
from opik.api_objects import span
from opik.decorator import arguments_helpers, base_track_decorator
from opik.types import LLMProvider

LOGGER = logging.getLogger(__name__)

KWARGS_KEYS_TO_LOG_AS_INPUTS = ["messages"]


class AISuiteTrackDecorator(base_track_decorator.BaseTrackDecorator):
    """
    An implementation of BaseTrackDecorator designed specifically for tracking
    calls of AISuite's `chat.completion.create`
    """

    @override
    def _start_span_inputs_preprocessor(
        self,
        func: Callable,
        track_options: arguments_helpers.TrackOptions,
        args: Optional[Tuple],
        kwargs: Optional[Dict[str, Any]],
    ) -> arguments_helpers.StartSpanParameters:
        assert (
            kwargs is not None
        ), "Expected kwargs to be not None in chat.completion.create(**kwargs)"

        name = track_options.name if track_options.name is not None else func.__name__
        metadata = track_options.metadata if track_options.metadata is not None else {}

        input, new_metadata = dict_utils.split_dict_by_keys(
            kwargs, keys=KWARGS_KEYS_TO_LOG_AS_INPUTS
        )
        metadata = dict_utils.deepmerge(metadata, new_metadata)
        metadata.update(
            {
                "created_from": "aisuite",
                "type": "aisuite_chat",
            }
        )

        tags = ["aisuite"]

        model, provider = self._get_provider_info(func, **kwargs)

        result = arguments_helpers.StartSpanParameters(
            name=name,
            input=input,
            type=track_options.type,
            tags=tags,
            metadata=metadata,
            project_name=track_options.project_name,
            model=model,
            provider=provider,
        )

        return result

    def _get_provider_info(
        self,
        func: Callable,
        **kwargs: Any,
    ) -> Tuple[Optional[str], Optional[str]]:
        provider: Optional[str] = None
        model: Optional[str] = kwargs.get("model", None)

        if model is not None and ":" in model:
            provider, model = model.split(":", 1)

        if provider != "openai":
            return model, provider

        if hasattr(func, "__self__") and func.__self__.client.providers.get("openai"):
            base_url_provider = func.__self__.client.providers.get("openai")
            base_url = base_url_provider.client.base_url
            if base_url.host != "api.openai.com":
                provider = base_url.host

        return model, provider

    @override
    def _end_span_inputs_preprocessor(
        self,
        output: Any,
        capture_output: bool,
        current_span_data: span.SpanData,
    ) -> arguments_helpers.EndSpanParameters:
        assert isinstance(
            output,
            (
                openai_chat_completion.ChatCompletion,  # openai
                aisuite_chat_completion.ChatCompletionResponse,  # non-openai
            ),
        )

        metadata = None
        usage = None
        model = None

        # provider == openai
        if isinstance(output, openai_chat_completion.ChatCompletion):
            result_dict = output.model_dump(mode="json")
            output, metadata = dict_utils.split_dict_by_keys(result_dict, ["choices"])
            if result_dict.get("usage") is not None:
                usage = llm_usage.try_build_opik_usage_or_log_error(
                    provider=LLMProvider.OPENAI,  # even if it's not openai, we know the format is openai-like
                    usage=result_dict["usage"],
                    logger=LOGGER,
                    error_message="Failed to log token usage from aisuite completion response",
                )
            else:
                usage = None

            model = result_dict["model"]

        # provider != openai
        elif isinstance(output, aisuite_chat_completion.ChatCompletionResponse):
            choices = []

            for choice in output.choices:
                choices.append(
                    {
                        "message": {"content": choice.message.content},
                    }
                )

            output = {"choices": choices}

        result = arguments_helpers.EndSpanParameters(
            output=output,
            usage=usage,
            metadata=metadata,
            model=model,
        )

        return result

    @override
    def _streams_handler(
        self,
        output: Any,
        capture_output: bool,
        generations_aggregator: Optional[Callable[[List[Any]], str]],
    ) -> Optional[Union[Generator, AsyncGenerator]]:
        return super()._streams_handler(output, capture_output, generations_aggregator)
