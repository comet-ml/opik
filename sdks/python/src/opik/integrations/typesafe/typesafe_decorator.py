import inspect
import logging
from typing import Any, Callable, Dict, List, Optional, Tuple

from typing_extensions import override

import opik.dict_utils as dict_utils
from opik.api_objects import span
from opik.decorator import arguments_helpers, base_track_decorator
from opik.llm_usage import opik_usage

LOGGER = logging.getLogger(__name__)

PROVIDER = "typesafe"

ARGUMENTS_TO_LOG_AS_INPUT = ["state", "questions"]
# Headers may carry credentials and `response_model` is a class, not request data.
# Every other argument (model, retry, timeout, extra_body) goes to metadata.
ARGUMENTS_NOT_LOGGED = {"extra_headers", "response_model"}
RESPONSE_KEYS_TO_LOG_AS_OUTPUT = ["answers"]


class TypeSafeTrackDecorator(base_track_decorator.BaseTrackDecorator):
    """Tracks ``typesafe_sdk`` ``system_one`` calls (sync and async clients)."""

    def __init__(self) -> None:
        super().__init__()
        self.provider = PROVIDER

    @override
    def _start_span_inputs_preprocessor(
        self,
        func: Callable,
        track_options: arguments_helpers.TrackOptions,
        args: Tuple,
        kwargs: Dict[str, Any],
    ) -> arguments_helpers.StartSpanParameters:
        name = track_options.name if track_options.name is not None else func.__name__

        arguments = {
            key: value
            for key, value in _bind_arguments(func, args, kwargs).items()
            if key not in ARGUMENTS_NOT_LOGGED
        }
        input, new_metadata = dict_utils.split_dict_by_keys(
            arguments, keys=ARGUMENTS_TO_LOG_AS_INPUT
        )

        metadata = track_options.metadata if track_options.metadata is not None else {}
        metadata = dict_utils.deepmerge(metadata, new_metadata)
        metadata.update({"created_from": "typesafe", "type": "typesafe_system_one"})

        model = arguments.get("model")

        return arguments_helpers.StartSpanParameters(
            name=name,
            input=input,
            type=track_options.type,
            tags=["typesafe"],
            metadata=metadata,
            project_name=track_options.project_name,
            model=model if isinstance(model, str) else None,
            provider=self.provider,
        )

    @override
    def _end_span_inputs_preprocessor(
        self,
        output: Any,
        capture_output: bool,
        current_span_data: span.SpanData,
    ) -> arguments_helpers.EndSpanParameters:
        result_dict = output.model_dump(mode="json")
        output_dict, metadata = dict_utils.split_dict_by_keys(
            result_dict, keys=RESPONSE_KEYS_TO_LOG_AS_OUTPUT
        )

        model = metadata.get("model")

        return arguments_helpers.EndSpanParameters(
            output=output_dict,
            usage=_try_build_opik_usage(metadata.get("usage")),
            metadata=metadata,
            model=model if isinstance(model, str) else current_span_data.model,
            provider=self.provider,
        )

    @override
    def _streams_handler(
        self,
        output: Any,
        capture_output: bool,
        generations_aggregator: Optional[Callable[[List[Any]], str]],
    ) -> Optional[Any]:
        # `system_one` returns the full response at once; the API has no streaming mode.
        NOT_A_STREAM = None
        return NOT_A_STREAM


def _bind_arguments(
    func: Callable, args: Tuple, kwargs: Dict[str, Any]
) -> Dict[str, Any]:
    """Maps positional and keyword arguments to parameter names.

    Falls back to the keyword arguments alone if the call does not match the
    signature; the wrapped call then surfaces the real error to the user.
    """
    try:
        return dict(inspect.signature(func).bind_partial(*args, **kwargs).arguments)
    except (TypeError, ValueError):
        return dict(kwargs)


def _try_build_opik_usage(usage: Any) -> Optional[opik_usage.OpikUsage]:
    if not isinstance(usage, dict):
        return None

    # The API reports `None` for token counts it did not measure.
    usage = {key: value for key, value in usage.items() if value is not None}
    if not usage:
        return None

    try:
        return opik_usage.OpikUsage.from_typesafe_dict(usage)
    except Exception:
        LOGGER.error("Failed to log token usage from typesafe call", exc_info=True)
        return None
