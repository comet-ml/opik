from typing import (
    List,
    Optional,
    Callable,
    AsyncGenerator,
    Generator,
    Union,
    Any,
    Dict,
    Tuple,
)


from opik.decorator import (
    arguments_helpers,
)
from opik.decorator import base_track_decorator
from opik.api_objects import span


class GuardrailsTrackDecorator(base_track_decorator.BaseTrackDecorator):
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
        assert isinstance(kwargs, dict)

        result = arguments_helpers.StartSpanParameters(
            name="Guardrail",
            input={"generation": kwargs["generation"]},
            type="guardrail",
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
