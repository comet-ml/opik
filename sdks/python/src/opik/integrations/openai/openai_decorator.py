import logging
from typing import List, Any, Dict, Optional, Callable, Tuple, Union

from opik import dict_utils
from opik.decorator import base_track_decorator, arguments_helpers
from . import stream_wrappers

import openai
from openai.types.chat import chat_completion

LOGGER = logging.getLogger(__name__)

CreateCallResult = Union[chat_completion.ChatCompletion, List[Any]]

KWARGS_KEYS_TO_LOG_AS_INPUTS = ["messages", "function_call"]
RESPONSE_KEYS_TO_LOG_AS_OUTPUT = ["choices"]


class OpenaiTrackDecorator(base_track_decorator.BaseTrackDecorator):
    """
    An implementation of BaseTrackDecorator designed specifically for tracking
    calls of OpenAI().chat.completion.create function.

    Besides special processing for input arguments and response content, it
    overrides _generators_handler() method to work correctly with
    openai.Stream and openai.AsyncStream objects.
    """

    def _start_span_inputs_preprocessor(
        self,
        func: Callable,
        track_options: arguments_helpers.TrackOptions,
        args: Optional[Tuple],
        kwargs: Optional[Dict[str, Any]],
    ) -> arguments_helpers.StartSpanParameters:
        assert (
            kwargs is not None
        ), "Expected kwargs to be not None in OpenAI().chat.completion.create(**kwargs)"
        name = track_options.name if track_options.name is not None else func.__name__
        metadata = track_options.metadata if track_options.metadata is not None else {}

        input, new_metadata = dict_utils.split_dict_by_keys(
            kwargs, keys=KWARGS_KEYS_TO_LOG_AS_INPUTS
        )
        metadata = dict_utils.deepmerge(metadata, new_metadata)
        metadata.update(
            {
                "created_from": "openai",
                "type": "openai_chat",
            }
        )

        tags = ["openai"]

        result = arguments_helpers.StartSpanParameters(
            name=name,
            input=input,
            type=track_options.type,
            tags=tags,
            metadata=metadata,
            project_name=track_options.project_name,
        )

        return result

    def _end_span_inputs_preprocessor(
        self, output: Any, capture_output: bool
    ) -> arguments_helpers.EndSpanParameters:
        assert isinstance(
            output,
            chat_completion.ChatCompletion,
        )

        result_dict = output.model_dump(mode="json")
        output, metadata = dict_utils.split_dict_by_keys(result_dict, ["choices"])
        usage = result_dict["usage"]

        result = arguments_helpers.EndSpanParameters(
            output=output,
            usage=usage,
            metadata=metadata,
        )

        return result

    def _generators_handler(
        self,
        output: Any,
        capture_output: bool,
        generations_aggregator: Optional[Callable[[List[Any]], Any]],
    ) -> Union[
        base_track_decorator.Generator[Any, None, None],
        base_track_decorator.AsyncGenerator[Any, None],
        None,
    ]:
        assert (
            generations_aggregator is not None
        ), "OpenAI decorator will always get aggregator function as input"

        if isinstance(output, openai.Stream):
            span_to_end, trace_to_end = base_track_decorator.pop_end_candidates()
            return stream_wrappers.wrap_sync_stream(
                generator=output,
                capture_output=capture_output,
                span_to_end=span_to_end,
                trace_to_end=trace_to_end,
                generations_aggregator=generations_aggregator,
                finally_callback=self._after_call,
            )

        if isinstance(output, openai.AsyncStream):
            span_to_end, trace_to_end = base_track_decorator.pop_end_candidates()
            return stream_wrappers.wrap_async_stream(
                generator=output,
                capture_output=capture_output,
                span_to_end=span_to_end,
                trace_to_end=trace_to_end,
                generations_aggregator=generations_aggregator,
                finally_callback=self._after_call,
            )

        NOT_A_STREAM = None

        return NOT_A_STREAM
