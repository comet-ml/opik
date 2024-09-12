import logging
import json
from typing import List, Any, Dict, Optional, Callable, Tuple, Union
from opik.types import SpanType
from opik.decorator import base_track_decorator, arguments_helpers
from . import stream_wrappers, chunks_aggregator

import openai
from openai.types.chat import chat_completion, chat_completion_message

LOGGER = logging.getLogger(__name__)

CreateCallResult = Union[chat_completion.ChatCompletion, List[Any]]


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
        name: Optional[str],
        type: SpanType,
        tags: Optional[List[str]],
        metadata: Optional[Dict[str, Any]],
        capture_input: bool,
        args: Optional[Tuple],
        kwargs: Optional[Dict[str, Any]],
    ) -> arguments_helpers.StartSpanParameters:
        assert (
            kwargs is not None
        ), "Expected kwargs to be not None in OpenAI().chat.completion.create(**kwargs)"
        kwargs_copy = kwargs.copy()

        name = name if name is not None else func.__name__

        input = {}
        input["messages"] = _parse_messages_list(kwargs_copy.pop("messages"))
        if "function_call" in kwargs_copy:
            input["function_call"] = kwargs_copy.pop("function_call")

        metadata = {
            "created_from": "openai",
            "type": "openai_chat",
            **kwargs_copy,
        }

        tags = ["openai"]

        result = arguments_helpers.StartSpanParameters(
            name=name, input=input, type=type, tags=tags, metadata=metadata
        )

        return result

    def _end_span_inputs_preprocessor(
        self, output: Any, capture_output: bool
    ) -> arguments_helpers.EndSpanParameters:
        assert isinstance(
            output,
            (chat_completion.ChatCompletion, chunks_aggregator.ExtractedStreamContent),
        )

        usage = None

        if isinstance(output, chat_completion.ChatCompletion):
            result_dict = output.model_dump(mode="json")
            choices: List[Dict[str, Any]] = result_dict.pop("choices")  # type: ignore
            output = {"choices": choices}

            usage = result_dict["usage"]
        elif isinstance(output, chunks_aggregator.ExtractedStreamContent):
            usage = output.usage
            output = {"choices": output.choices}

        result = arguments_helpers.EndSpanParameters(output=output, usage=usage)

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


def _parse_messages_list(
    messages: List[
        Union[Dict[str, Any], chat_completion_message.ChatCompletionMessage]
    ],
) -> List[Dict[str, Any]]:
    if _is_jsonable(messages):
        return messages

    result = []

    for message in messages:
        if _is_jsonable(message):
            result.append(message)
            continue

        if isinstance(message, chat_completion_message.ChatCompletionMessage):
            result.append(message.model_dump(mode="json"))
            continue

        LOGGER.debug("Message %s is not json serializable", message)

        result.append(
            str(message)
        )  # TODO: replace with Opik serializer when it is implemented

    return result


def _is_jsonable(x: Any) -> bool:
    try:
        json.dumps(x)
        return True
    except (TypeError, OverflowError):
        return False
