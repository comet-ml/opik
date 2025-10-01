import logging
import json
from typing import Optional

import botocore.response
import functools

import opik.api_objects.span as span
import opik.api_objects.trace as trace
from opik.types import ErrorInfoDict
from opik.decorator import generator_wrappers, error_info_collector
from .. import helpers

LOGGER = logging.getLogger(__name__)


__original_streaming_body_read = botocore.response.StreamingBody.read


def wrap_invoke_model_response(
    output: helpers.InvokeModelOutput,
    span_to_end: span.SpanData,
    trace_to_end: Optional[trace.TraceData],
    finally_callback: generator_wrappers.FinishGeneratorCallback,
) -> helpers.InvokeModelOutput:
    response_metadata = output["ResponseMetadata"]
    streaming_body = output["body"]

    @functools.wraps(__original_streaming_body_read)
    def wrapped_read(self: botocore.response.StreamingBody, *args, **kwargs):  # type: ignore
        error_info: Optional[ErrorInfoDict] = None
        try:
            result = __original_streaming_body_read(self, *args, **kwargs)
            return result
        except Exception as exception:
            LOGGER.debug(
                "Exception raised from botocore.response.StreamingBody.",
                str(exception),
                exc_info=True,
            )
            error_info = error_info_collector.collect(exception)
            raise exception
        finally:
            if not hasattr(self, "opik_tracked_instance"):
                return

            delattr(self, "opik_tracked_instance")

            if error_info is None:
                output = {
                    "body": json.loads(result),
                    "ResponseMetadata": response_metadata,
                }
            else:
                output = {"body": None, "ResponseMetadata": response_metadata}

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
