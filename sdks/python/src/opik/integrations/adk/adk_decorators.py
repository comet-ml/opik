import os
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

import opik.types as opik_types
from opik.api_objects import span
from opik.decorator import arguments_helpers, base_track_decorator

from . import llm_response_wrapper


def convert_adk_base_models(arg: Any) -> Dict[str, Any]:
    """Most ADK objects are Pydantic Base Models"""
    return arg.model_dump(mode="json", exclude_unset=True)


def get_adk_provider() -> opik_types.LLMProvider:
    use_vertexai = os.environ.get("GOOGLE_GENAI_USE_VERTEXAI", "0").lower() in [
        "true",
        "1",
    ]
    return (
        opik_types.LLMProvider.GOOGLE_VERTEXAI
        if use_vertexai
        else opik_types.LLMProvider.GOOGLE_AI
    )


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
        usage_data = llm_response_wrapper.pop_llm_usage_data(**result_dict)

        if usage_data is not None:
            result = arguments_helpers.EndSpanParameters(
                output=result_dict,
                usage=usage_data.opik_usage,
                model=usage_data.model,
                provider=get_adk_provider(),
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
