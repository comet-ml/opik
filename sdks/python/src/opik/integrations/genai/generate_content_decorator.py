import logging
from typing import (
    Any,
    AsyncIterator,
    Callable,
    Dict,
    Iterator,
    List,
    Optional,
    Tuple,
    Union,
)

from google.genai import types as genai_types

from opik import dict_utils
from opik.decorator import arguments_helpers, base_track_decorator
from opik import llm_usage

from . import stream_wrappers

LOGGER = logging.getLogger(__name__)


KWARGS_KEYS_TO_LOG_AS_INPUTS = ["contents", "config"]
RESPONSE_KEYS_TO_LOG_AS_OUTPUT = ["candidates"]


class GenerateContentTrackDecorator(base_track_decorator.BaseTrackDecorator):
    """
    An implementation of BaseTrackDecorator designed specifically for tracking
    calls of OpenAI's `chat.completion.create` and `chat.completions.parse` functions.

    Besides special processing for input arguments and response content, it
    overrides _generators_handler() method to work correctly with
    openai.Stream and openai.AsyncStream objects.
    """

    def __init__(self, provider: str) -> None:
        super().__init__()
        self.provider = provider

    def _start_span_inputs_preprocessor(
        self,
        func: Callable,
        track_options: arguments_helpers.TrackOptions,
        args: Optional[Tuple],
        kwargs: Optional[Dict[str, Any]],
    ) -> arguments_helpers.StartSpanParameters:
        assert (
            kwargs is not None
        ), "Expected kwargs to be not None in client.models.generate_content(**kwargs), client.aio.models.generate_content(**kwargs)"

        name = track_options.name if track_options.name is not None else func.__name__

        metadata = track_options.metadata if track_options.metadata is not None else {}

        input, new_metadata = dict_utils.split_dict_by_keys(
            kwargs, keys=KWARGS_KEYS_TO_LOG_AS_INPUTS
        )
        metadata = dict_utils.deepmerge(metadata, new_metadata)
        metadata.update({"created_from": "genai"})

        tags = ["genai"]

        result = arguments_helpers.StartSpanParameters(
            name=name,
            input=input,
            type=track_options.type,
            tags=tags,
            metadata=metadata,
            project_name=track_options.project_name,
            model=kwargs.get("model", None),
            provider=self.provider,
        )

        return result

    def _end_span_inputs_preprocessor(
        self, output: Any, capture_output: bool
    ) -> arguments_helpers.EndSpanParameters:
        assert isinstance(
            output,
            genai_types.GenerateContentResponse,
        )

        result_dict = output.model_dump(mode="json")
        output, metadata = dict_utils.split_dict_by_keys(
            result_dict, RESPONSE_KEYS_TO_LOG_AS_OUTPUT
        )

        model = result_dict["model_version"]
        usage = llm_usage.opik_usage_from_google_format(result_dict["usage_metadata"])

        result = arguments_helpers.EndSpanParameters(
            output=output,
            usage=usage,
            metadata=metadata,
            model=model,
            provider=self.provider,
        )

        return result

    def _streams_handler(
        self,
        output: Any,
        capture_output: bool,
        generations_aggregator: Optional[
            Callable[
                [List[genai_types.GenerateContentResponse]],
                genai_types.GenerateContentResponse,
            ]
        ],
    ) -> Union[
        None,
        Iterator[genai_types.GenerateContentResponse],
        AsyncIterator[genai_types.GenerateContentResponse],
    ]:
        assert generations_aggregator is not None

        if isinstance(output, Iterator):
            span_to_end, trace_to_end = base_track_decorator.pop_end_candidates()
            return stream_wrappers.wrap_sync_iterator(
                stream=output,
                span_to_end=span_to_end,
                trace_to_end=trace_to_end,
                generations_aggregator=generations_aggregator,
                finally_callback=self._after_call,
            )

        if isinstance(output, AsyncIterator):
            span_to_end, trace_to_end = base_track_decorator.pop_end_candidates()
            return stream_wrappers.wrap_async_iterator(
                stream=output,
                span_to_end=span_to_end,
                trace_to_end=trace_to_end,
                generations_aggregator=generations_aggregator,
                finally_callback=self._after_call,
            )

        NO_STREAM_DETECTED = None
        return NO_STREAM_DETECTED
