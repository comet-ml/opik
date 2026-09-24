import inspect
import logging
from typing import Any, Callable, Dict, List, Optional, Tuple
from typing_extensions import override

from ..api_objects import opik_client, span
from . import arguments_helpers, base_track_decorator, inspect_helpers

LOGGER = logging.getLogger(__name__)


class OpikTrackDecorator(base_track_decorator.BaseTrackDecorator):
    """
    Default implementation of BaseTrackDecorator
    """

    @override
    def _start_span_inputs_preprocessor(
        self,
        func: Callable,
        track_options: arguments_helpers.TrackOptions,
        args: Tuple,
        kwargs: Dict[str, Any],
    ) -> arguments_helpers.StartSpanParameters:
        input = (
            inspect_helpers.extract_inputs(func, args, kwargs)
            if track_options.capture_input
            else None
        )

        if input is not None and track_options.ignore_arguments is not None:
            ignored = set(track_options.ignore_arguments)
            for argument in ignored:
                input.pop(argument, None)

            # Arguments passed through **kwargs are captured as one nested dict. Build
            # a filtered copy rather than popping, since it can be the caller's dict.
            for key in (_var_keyword_parameter_name(func), "kwargs"):
                nested = input.get(key) if key is not None else None
                if isinstance(nested, dict) and ignored & nested.keys():
                    input[key] = {k: v for k, v in nested.items() if k not in ignored}

        name = (
            track_options.name
            if track_options.name is not None
            else inspect_helpers.get_function_name(func)
        )

        result = arguments_helpers.StartSpanParameters(
            name=name,
            input=input,
            type=track_options.type,
            tags=track_options.tags,
            metadata=track_options.metadata,
            project_name=track_options.project_name,
            environment=track_options.environment,
        )

        return result

    @override
    def _end_span_inputs_preprocessor(
        self,
        output: Any,
        capture_output: bool,
        current_span_data: span.SpanData,
    ) -> arguments_helpers.EndSpanParameters:
        output = output if capture_output else None

        if output is not None and not isinstance(output, dict):
            output = {"output": output}

        result = arguments_helpers.EndSpanParameters(output=output)

        return result

    @override
    def _streams_handler(
        self,
        output: Any,
        capture_output: bool,
        generations_aggregator: Optional[Callable[[List[Any]], str]],
    ) -> Optional[Any]:
        return super()._streams_handler(output, capture_output, generations_aggregator)


def flush_tracker(timeout: Optional[int] = None) -> None:
    opik_ = opik_client.get_global_client()
    opik_.flush(timeout)


_decorator = OpikTrackDecorator()


track = _decorator.track


def _var_keyword_parameter_name(func: Callable) -> Optional[str]:
    try:
        parameters = inspect.signature(func).parameters.values()
    except (TypeError, ValueError):
        return None
    return next(
        (p.name for p in parameters if p.kind is inspect.Parameter.VAR_KEYWORD), None
    )
