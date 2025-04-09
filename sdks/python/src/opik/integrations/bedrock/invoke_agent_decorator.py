import logging
from typing import Any, Callable, Dict, List, Optional, Tuple, Union, cast
from typing_extensions import override

from opik import dict_utils
from opik.api_objects import span
from opik.decorator import arguments_helpers, base_track_decorator

from . import helpers, stream_wrappers

LOGGER = logging.getLogger(__name__)

KWARGS_KEYS_TO_LOG_AS_INPUTS = ["inputText"]
RESPONSE_KEYS_TO_LOG_AS_OUTPUTS = ["output"]


class BedrockInvokeAgentDecorator(base_track_decorator.BaseTrackDecorator):
    """
    An implementation of BaseTrackDecorator designed specifically for tracking
    calls of AWS bedrock client `invoke_agent` function.

    Besides special processing for input arguments and response content, it
    overrides _generators_handler() method to work correctly with bedrock's streams
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
        ), "Expected kwargs to be not None in BedrockRuntime.Client.invoke_agent(**kwargs)"

        name = track_options.name if track_options.name is not None else func.__name__
        input, metadata = dict_utils.split_dict_by_keys(
            kwargs, KWARGS_KEYS_TO_LOG_AS_INPUTS
        )
        metadata["created_from"] = "bedrock"
        tags = ["bedrock"]

        result = arguments_helpers.StartSpanParameters(
            name=name,
            input=input,
            type=track_options.type,
            tags=tags,
            metadata=metadata,
            project_name=track_options.project_name,
        )

        return result

    @override
    def _end_span_inputs_preprocessor(
        self,
        output: Any,
        capture_output: bool,
        current_span_data: span.SpanData,
    ) -> arguments_helpers.EndSpanParameters:
        output, metadata = dict_utils.split_dict_by_keys(
            output, RESPONSE_KEYS_TO_LOG_AS_OUTPUTS
        )
        result = arguments_helpers.EndSpanParameters(
            output=output,
            metadata=metadata,
        )

        return result

    @override
    def _streams_handler(  # type: ignore
        self,
        output: Any,
        capture_output: bool,
        generations_aggregator: Optional[Callable[[List[Any]], Any]],
    ) -> Union[
        helpers.ConverseStreamOutput,
        None,
    ]:
        DECORATED_FUNCTION_IS_NOT_EXPECTED_TO_RETURN_GENERATOR = (
            generations_aggregator is None
        )

        if DECORATED_FUNCTION_IS_NOT_EXPECTED_TO_RETURN_GENERATOR:
            return None

        assert generations_aggregator is not None

        if isinstance(output, dict) and "completion" in output:
            span_to_end, trace_to_end = base_track_decorator.pop_end_candidates()

            wrapped_stream = stream_wrappers.wrap_stream(
                stream=output["completion"],
                capture_output=capture_output,
                span_to_end=span_to_end,
                trace_to_end=trace_to_end,
                generations_aggregator=generations_aggregator,
                response_metadata=output["ResponseMetadata"],
                finally_callback=self._after_call,
            )

            output["completion"] = wrapped_stream
            return cast(helpers.ConverseStreamOutput, output)

        STREAM_NOT_FOUND = None

        return STREAM_NOT_FOUND
