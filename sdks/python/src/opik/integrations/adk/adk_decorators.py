import dataclasses
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


from opik.api_objects import span
from opik.decorator import arguments_helpers, base_track_decorator
from . import llm_response_wrapper
from ... import llm_usage, LLMProvider
from ...llm_usage import opik_usage

LOGGER = logging.Logger(__name__)


@dataclasses.dataclass
class UsageData:
    opik_usage: opik_usage.OpikUsage
    model: str
    provider: str


def convert_adk_base_models(arg: Any) -> Dict[str, Any]:
    """Most ADK objects are Pydantic Base Models"""
    return arg.model_dump(mode="json", exclude_unset=True)


def pop_opik_usage(**result_dict: Dict[str, Any]) -> Optional[UsageData]:
    """Extracts Opik usage metadata from ADK output and removes it from the result dict."""
    custom_metadata = result_dict.get("custom_metadata", None)
    if custom_metadata is None:
        return None

    opik_usage_metadata = custom_metadata.pop(
        llm_response_wrapper.OPIK_USAGE_METADATA_KEY, None
    )
    if opik_usage_metadata is None:
        return None

    model = custom_metadata.pop("model_version", None)
    provider = custom_metadata.pop("provider", None)
    usage = llm_usage.try_build_opik_usage_or_log_error(
        provider=LLMProvider(provider),
        usage=opik_usage_metadata,
        logger=LOGGER,
        error_message="Failed to log token usage from ADK Gemini call",
    )
    return UsageData(opik_usage=usage, model=model, provider=provider)


class ADKLLMTrackDecorator(base_track_decorator.BaseTrackDecorator):
    """
    An implementation of BaseTrackDecorator designed specifically for guardrails span.
    """

    def _start_span_inputs_preprocessor(
        self,
        func: Callable,
        track_options: arguments_helpers.TrackOptions,
        args: Optional[Tuple],
        kwargs: Optional[Dict[str, Any]],
    ) -> arguments_helpers.StartSpanParameters:
        if args is None:
            raise ValueError("args cannot be None")

        result = arguments_helpers.StartSpanParameters(
            name=track_options.name or "llm",
            input=convert_adk_base_models(args[0]),
            type="llm",
        )

        return result

    def _end_span_inputs_preprocessor(
        self,
        output: Any,
        capture_output: bool,
        current_span_data: span.SpanData,
    ) -> arguments_helpers.EndSpanParameters:
        result_dict = convert_adk_base_models(output)
        usage_data = pop_opik_usage(**result_dict)

        if usage_data is not None:
            result = arguments_helpers.EndSpanParameters(
                output=result_dict,
                usage=usage_data.opik_usage,
                model=usage_data.model,
            )
        else:
            result = arguments_helpers.EndSpanParameters(
                output=result_dict,
            )

        return result

    def _streams_handler(
        self,
        output: Any,
        capture_output: bool,
        generations_aggregator: Optional[Callable[[List[Any]], str]],
    ) -> Optional[Union[Generator, AsyncGenerator]]:
        return super()._streams_handler(output, capture_output, generations_aggregator)


class ADKToolTrackDecorator(base_track_decorator.BaseTrackDecorator):
    """
    An implementation of BaseTrackDecorator designed specifically for guardrails span.
    """

    def _start_span_inputs_preprocessor(
        self,
        func: Callable,
        track_options: arguments_helpers.TrackOptions,
        args: Optional[Tuple],
        kwargs: Optional[Dict[str, Any]],
    ) -> arguments_helpers.StartSpanParameters:
        if args is None:
            raise ValueError("args cannot be None")

        result = arguments_helpers.StartSpanParameters(
            name=track_options.name or "tool",
            input=args[0],
            type="tool",
        )

        return result

    def _end_span_inputs_preprocessor(
        self,
        output: Any,
        capture_output: bool,
        current_span_data: span.SpanData,
    ) -> arguments_helpers.EndSpanParameters:
        result = arguments_helpers.EndSpanParameters(
            output=output,
        )

        return result

    def _streams_handler(
        self,
        output: Any,
        capture_output: bool,
        generations_aggregator: Optional[Callable[[List[Any]], str]],
    ) -> Optional[Union[Generator, AsyncGenerator]]:
        return super()._streams_handler(output, capture_output, generations_aggregator)
