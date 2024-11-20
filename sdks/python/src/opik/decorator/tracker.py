import logging

from typing import List, Any, Dict, Optional, Callable, Tuple, Union


from . import inspect_helpers, arguments_helpers
from ..api_objects import opik_client
from . import base_track_decorator

LOGGER = logging.getLogger(__name__)


class OpikTrackDecorator(base_track_decorator.BaseTrackDecorator):
    """
    Default implementation of BaseTrackDecorator
    """

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
            for argument in track_options.ignore_arguments:
                input.pop(argument, None)

        name = track_options.name if track_options.name is not None else func.__name__

        result = arguments_helpers.StartSpanParameters(
            name=name,
            input=input,
            type=track_options.type,
            tags=track_options.tags,
            metadata=track_options.metadata,
            project_name=track_options.project_name,
        )

        return result

    def _end_span_inputs_preprocessor(
        self, output: Any, capture_output: bool
    ) -> arguments_helpers.EndSpanParameters:
        output = output if capture_output else None

        if output is not None and not isinstance(output, dict):
            output = {"output": output}

        result = arguments_helpers.EndSpanParameters(output=output)

        return result

    def _generators_handler(
        self,
        output: Any,
        capture_output: bool,
        generations_aggregator: Optional[Callable[[List[Any]], str]],
    ) -> Union[
        base_track_decorator.Generator[Any, None, None],
        base_track_decorator.AsyncGenerator[Any, None],
        None,
    ]:
        return super()._generators_handler(
            output, capture_output, generations_aggregator
        )


def flush_tracker(timeout: Optional[int] = None) -> None:
    opik_ = opik_client.get_client_cached()
    opik_.flush(timeout)


_decorator = OpikTrackDecorator()


track = _decorator.track
