import logging
import json
from typing import Optional, Callable, List, Any, Dict, Generator

import botocore.response
import functools

import opik.api_objects.span as span
import opik.api_objects.trace as trace
from opik.types import ErrorInfoDict
from opik.decorator import generator_wrappers, error_info_collector
from .. import types

import botocore.eventstream

LOGGER = logging.getLogger(__name__)


__original_streaming_body_read = botocore.response.StreamingBody.read


def wrap_invoke_model_response(
    output: types.InvokeModelOutput,
    span_to_end: span.SpanData,
    trace_to_end: Optional[trace.TraceData],
    finally_callback: generator_wrappers.FinishGeneratorCallback,
) -> types.InvokeModelOutput:
    response_metadata = output["ResponseMetadata"]
    streaming_body = output["body"]

    @functools.wraps(__original_streaming_body_read)
    def wrapped_read(self: botocore.response.StreamingBody, *args, **kwargs):  # type: ignore
        error_info: Optional[ErrorInfoDict] = None
        result = None
        try:
            result = __original_streaming_body_read(self, *args, **kwargs)
            return result
        except Exception as exception:
            LOGGER.debug(
                "Exception raised from botocore.response.StreamingBody: %s",
                str(exception),
                exc_info=True,
            )
            error_info = error_info_collector.collect(exception)
            raise exception
        finally:
            if not hasattr(self, "opik_tracked_instance"):
                return None

            delattr(self, "opik_tracked_instance")

            if error_info is None and result is not None:
                try:
                    parsed_body = json.loads(result)
                    output = {
                        "body": parsed_body,
                        "ResponseMetadata": response_metadata,
                    }
                    LOGGER.debug(
                        "Successfully parsed response body with keys: %s",
                        list(parsed_body.keys()),
                    )
                except (json.JSONDecodeError, TypeError) as e:
                    LOGGER.debug("Failed to parse response body as JSON: %s", e)
                    output = {"body": {}, "ResponseMetadata": response_metadata}
            else:
                LOGGER.debug("Error occurred or result is None, using empty body")
                output = {"body": {}, "ResponseMetadata": response_metadata}

            finally_callback(
                output=output,
                error_info=error_info,
                generators_span_to_end=span_to_end,
                generators_trace_to_end=trace_to_end,
                capture_output=True,
            )

    botocore.response.StreamingBody.read = wrapped_read
    streaming_body.opik_tracked_instance = True

    return output


def wrap_invoke_model_with_response_stream_response(
    stream: botocore.eventstream.EventStream,
    capture_output: bool,
    span_to_end: span.SpanData,
    trace_to_end: Optional[trace.TraceData],
    generations_aggregator: Callable[[List[Any]], Any],
    response_metadata: Dict[str, Any],
    finally_callback: generator_wrappers.FinishGeneratorCallback,
) -> Generator[Any, None, None]:
    items: List[Dict[str, Any]] = []
    error_info: Optional[ErrorInfoDict] = None

    try:
        for item in stream:
            items.append(item)

            yield item
    except Exception as exception:
        LOGGER.debug(
            "Exception raised from botocore.eventstream.EventStream: %s",
            str(exception),
            exc_info=True,
        )
        error_info = error_info_collector.collect(exception)
        raise exception
    finally:
        if error_info is None:
            output = generations_aggregator(items)
            output.response_metadata = response_metadata
        else:
            output = None

        finally_callback(
            output=output,
            error_info=error_info,
            generators_span_to_end=span_to_end,
            generators_trace_to_end=trace_to_end,
            capture_output=capture_output,
        )
