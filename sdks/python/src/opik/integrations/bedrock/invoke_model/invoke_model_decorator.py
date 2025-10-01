import json
import logging
from typing import Any, Callable, Dict, List, Optional, Tuple, Union, cast
from typing_extensions import override

import opik
import opik.dict_utils as dict_utils
from opik.api_objects import span
from opik.decorator import arguments_helpers, base_track_decorator

from .. import helpers
from . import stream_wrappers, usage_extraction

import botocore.response

LOGGER = logging.getLogger(__name__)

# Keys to extract from kwargs for input logging
KWARGS_KEYS_TO_LOG_AS_INPUTS = ["body", "modelId"]
# Keys to extract from response for output logging
RESPONSE_KEYS_TO_LOG_AS_OUTPUTS = ["body"]


class BedrockInvokeModelDecorator(base_track_decorator.BaseTrackDecorator):
    """
    An implementation of BaseTrackDecorator designed specifically for tracking
    calls of AWS bedrock client `invoke_model` and `invoke_model_with_response_stream` functions.

    Besides special processing for input arguments and response content, it
    overrides _streams_handler() method to work correctly with bedrock's streams
    """

    @override
    def _start_span_inputs_preprocessor(
        self,
        func: Callable,
        track_options: arguments_helpers.TrackOptions,
        args: Tuple,
        kwargs: Dict[str, Any],
    ) -> arguments_helpers.StartSpanParameters:
        assert (
            kwargs is not None
        ), "Expected kwargs to be not None in BedrockRuntime.Client.invoke_model(**kwargs)"

        name = track_options.name if track_options.name is not None else func.__name__
        body_dict = json.loads(kwargs["body"])

        kwargs_copy = kwargs.copy()
        kwargs_copy["body"] = body_dict

        input_data, metadata = dict_utils.split_dict_by_keys(
            kwargs_copy, KWARGS_KEYS_TO_LOG_AS_INPUTS
        )

        metadata["created_from"] = "bedrock"
        tags = ["bedrock", "invoke_model"]

        result = arguments_helpers.StartSpanParameters(
            name=name,
            input=input_data,
            type=track_options.type,
            tags=tags,
            metadata=metadata,
            project_name=track_options.project_name,
            model=kwargs.get("modelId", None),
            provider=opik.LLMProvider.BEDROCK,
        )

        return result

    @override
    def _end_span_inputs_preprocessor(
        self,
        output: Any,
        capture_output: bool,
        current_span_data: span.SpanData,
    ) -> arguments_helpers.EndSpanParameters:
        output = cast(Dict[str, Any], output)
        output, metadata = dict_utils.split_dict_by_keys(
            output, RESPONSE_KEYS_TO_LOG_AS_OUTPUTS
        )
        subprovider = _extract_subprovider_from_model_id(
            cast(str, current_span_data.model)
        )
        opik_usage = usage_extraction.try_extract_usage_from_bedrock_response(
            subprovider, output
        )

        result = arguments_helpers.EndSpanParameters(
            output=output,
            provider=opik.LLMProvider.BEDROCK,
            usage=opik_usage,
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
        helpers.InvokeModelOutput,
        None,
    ]:
        DECORATED_FUNCTION_IS_NOT_EXPECTED_TO_RETURN_GENERATOR = (
            generations_aggregator is None
        )

        if DECORATED_FUNCTION_IS_NOT_EXPECTED_TO_RETURN_GENERATOR:
            return None

        assert generations_aggregator is not None

        # Despite the name, StreamingBody is not a stream in traditional LLM provider sense (response chunks).
        # It's an interface to a stream of bytes representing the response body.
        streaming_body_detected = (
            isinstance(output, dict)
            and "body" in output
            and isinstance(output["body"], botocore.response.StreamingBody)
        )

        if streaming_body_detected:
            span_to_end, trace_to_end = base_track_decorator.pop_end_candidates()
            return stream_wrappers.wrap_invoke_model_response(
                output=output,
                span_to_end=span_to_end,
                trace_to_end=trace_to_end,
                finally_callback=self._after_call,
            )

        STREAM_NOT_FOUND = None

        return STREAM_NOT_FOUND


def _extract_subprovider_from_model_id(model_id: str) -> str:
    """
    Extracts the subprovider name from a Bedrock modelId.

    Examples:
        ai21.j2-mid-v1                -> ai21
        amazon.nova-lite-v1:0         -> amazon
        anthropic.claude-v2:1         -> anthropic
        us.meta.llama3-1-70b-instruct -> meta
    """
    parts = model_id.split(".")

    if parts[0] in {"us", "eu", "apac"}:
        return parts[1]

    return parts[0]
