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
from typing_extensions import override

from google.genai import types as genai_types

import opik.dict_utils as dict_utils
import opik.llm_usage as llm_usage
from opik.api_objects import span
from opik.decorator import arguments_helpers, base_track_decorator
from opik.types import LLMProvider

from . import stream_wrappers

LOGGER = logging.getLogger(__name__)


KWARGS_KEYS_TO_LOG_AS_INPUTS = ["contents", "config"]
RESPONSE_KEYS_TO_LOG_AS_OUTPUT = ["candidates"]


class GenerateContentTrackDecorator(base_track_decorator.BaseTrackDecorator):
    """
    An implementation of BaseTrackDecorator designed specifically for tracking
    calls to genai.Client's
    * models.generate_content
    * models.generate_content_stream
    * aio.models.generate_content
    * aio.models.generate_content_stream
    """

    def __init__(self, provider: str) -> None:
        super().__init__()
        self.provider = provider

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
        ), "Expected kwargs to be not None in client.models.generate_content(**kwargs), client.aio.models.generate_content(**kwargs)"

        model = kwargs.get("model")

        name = track_options.name if track_options.name is not None else func.__name__
        name = f"{name}: {model}"  # Add model to the name for better viewing UX

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
            model=model,
            provider=self.provider,
        )

        return result

    @override
    def _end_span_inputs_preprocessor(
        self,
        output: Any,
        capture_output: bool,
        current_span_data: span.SpanData,
    ) -> arguments_helpers.EndSpanParameters:
        assert isinstance(
            output,
            genai_types.GenerateContentResponse,
        ), f"{output}"

        result_dict = output.model_dump(mode="json")
        output, metadata = dict_utils.split_dict_by_keys(
            result_dict, RESPONSE_KEYS_TO_LOG_AS_OUTPUT
        )

        if result_dict.get("model_version") is not None:
            # Gemini **may** add "models/" prefix to some model versions
            model = result_dict["model_version"].split("/")[-1]
        else:
            model = None

        usage = llm_usage.try_build_opik_usage_or_log_error(
            provider=LLMProvider(self.provider),
            usage=result_dict["usage_metadata"],
            logger=LOGGER,
            error_message="Failed to log token usage from genai generate_response call",
        )
        span_name_without_model = current_span_data.name.split(":")[0]  # type: ignore
        result = arguments_helpers.EndSpanParameters(
            name=f"{span_name_without_model}: {model}",
            output=output,
            usage=usage,
            metadata=metadata,
            model=model,
            provider=self.provider,
        )

        return result

    @override
    def _streams_handler(
        self,
        output: Any,
        capture_output: bool,
        generations_aggregator: Optional[Callable[[List[Any]], Any]],
    ) -> Union[
        None,
        Iterator[genai_types.GenerateContentResponse],
        AsyncIterator[genai_types.GenerateContentResponse],
    ]:
        if isinstance(output, Iterator):
            span_to_end, trace_to_end = base_track_decorator.pop_end_candidates()
            assert generations_aggregator is not None
            return stream_wrappers.wrap_sync_iterator(
                stream=output,
                span_to_end=span_to_end,
                trace_to_end=trace_to_end,
                generations_aggregator=generations_aggregator,
                finally_callback=self._after_call,
            )

        if isinstance(output, AsyncIterator):
            span_to_end, trace_to_end = base_track_decorator.pop_end_candidates()
            assert generations_aggregator is not None
            return stream_wrappers.wrap_async_iterator(
                stream=output,
                span_to_end=span_to_end,
                trace_to_end=trace_to_end,
                generations_aggregator=generations_aggregator,
                finally_callback=self._after_call,
            )

        NO_STREAM_DETECTED = None
        return NO_STREAM_DETECTED
