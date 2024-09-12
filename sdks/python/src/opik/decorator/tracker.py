import logging

from typing import List, Any, Dict, Optional, Callable, Tuple

from ..types import SpanType

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
        name: Optional[str],
        type: SpanType,
        tags: Optional[List[str]],
        metadata: Optional[Dict[str, Any]],
        capture_input: bool,
        args: Tuple,
        kwargs: Dict[str, Any],
    ) -> arguments_helpers.StartSpanParameters:
        input = (
            inspect_helpers.extract_inputs(func, args, kwargs)
            if capture_input
            else None
        )

        name = name if name is not None else func.__name__

        result = arguments_helpers.StartSpanParameters(
            name=name, input=input, type=type, tags=tags, metadata=metadata
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


def flush_tracker(timeout: Optional[int] = None) -> None:
    opik_ = opik_client.get_client_cached()
    opik_.flush(timeout)


_decorator = OpikTrackDecorator()


track = _decorator.track
