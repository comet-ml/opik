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
from typing_extensions import override

from guardrails import validators

from opik.api_objects import span
from opik.decorator import arguments_helpers, base_track_decorator, inspect_helpers

LOGGER = logging.getLogger(__name__)

KWARGS_KEYS_TO_LOG_AS_INPUTS = ["value"]
RESPONSE_KEYS_TO_LOG_AS_OUTPUT = ["output"]


class GuardrailsValidatorValidateDecorator(base_track_decorator.BaseTrackDecorator):
    @override
    def _start_span_inputs_preprocessor(
        self,
        func: Callable,
        track_options: arguments_helpers.TrackOptions,
        args: Tuple,
        kwargs: Dict[str, Any],
    ) -> arguments_helpers.StartSpanParameters:
        name = track_options.name if track_options.name is not None else func.__name__
        metadata = track_options.metadata if track_options.metadata is not None else {}
        metadata.update({"created_from": "guardrails"})
        input = (
            inspect_helpers.extract_inputs(func, args, kwargs)
            if track_options.capture_input
            else None
        )

        validator_instance = func.__self__  # type: ignore
        model = (
            validator_instance.llm_callable
            if hasattr(validator_instance, "llm_callable")
            else None
        )
        if model is not None:
            metadata["model"] = model

        result = arguments_helpers.StartSpanParameters(
            name=name,
            input=input,
            type=track_options.type,
            metadata=metadata,
            project_name=track_options.project_name,
            model=model,
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
            validators.ValidationResult,
        )
        tags = ["guardrails", output.outcome]

        result = arguments_helpers.EndSpanParameters(
            output=output,
            metadata=output.metadata,
            tags=tags,
        )

        return result

    @override
    def _streams_handler(
        self,
        output: Any,
        capture_output: bool,
        generations_aggregator: Optional[Callable[[List[Any]], str]],
    ) -> Optional[Union[Generator, AsyncGenerator]]:
        return super()._streams_handler(output, capture_output, generations_aggregator)
