from typing import Any, Callable, Dict, List, Optional, Tuple

from typing_extensions import override

from opik.api_objects import opik_client, span
from opik.decorator import arguments_helpers, base_track_decorator, inspect_helpers
from tests.testlib import ANY_BUT_NONE, ANY_STRING, SpanModel, TraceModel, assert_equal


class BrokenOpikTrackDecorator(base_track_decorator.BaseTrackDecorator):
    def __init__(
        self,
        start_span_preprocessor_error: bool = False,
        end_span_preprocessor_error: bool = False,
    ):
        super().__init__()
        self.start_span_preprocessor_error = start_span_preprocessor_error
        self.end_span_preprocessor_error = end_span_preprocessor_error

    @override
    def _start_span_inputs_preprocessor(
        self,
        func: Callable,
        track_options: arguments_helpers.TrackOptions,
        args: Tuple,
        kwargs: Dict[str, Any],
    ) -> arguments_helpers.StartSpanParameters:
        if self.start_span_preprocessor_error:
            raise Exception("Some error happened during span creation preprocessing")

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

    @override
    def _end_span_inputs_preprocessor(
        self,
        output: Any,
        capture_output: bool,
        current_span_data: span.SpanData,
    ) -> arguments_helpers.EndSpanParameters:
        if self.end_span_preprocessor_error:
            raise Exception("Some error happened during span creation postprocessing")

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

    def flush_tracker(self) -> None:
        opik_ = opik_client.get_client_cached()
        opik_.flush()


def test_broken_decorator__start_span_preprocessor__no_error(fake_backend):
    tracker_instance = BrokenOpikTrackDecorator(start_span_preprocessor_error=True)

    @tracker_instance.track
    def f_inner(num: int) -> int:
        return num

    f_inner(42)

    tracker_instance.flush_tracker()

    EXPECTED_TRACE_TREE = TraceModel(
        end_time=ANY_BUT_NONE,
        id=ANY_STRING(),
        input=None,
        name="f_inner",
        output={"output": 42},
        start_time=ANY_BUT_NONE,
        error_info=None,
        spans=[
            SpanModel(
                end_time=ANY_BUT_NONE,
                id=ANY_STRING(),
                input=None,
                name="f_inner",
                output={"output": 42},
                start_time=ANY_BUT_NONE,
                type="general",
                spans=[],
                error_info=None,
            ),
        ],
    )

    assert len(fake_backend.trace_trees) == 1
    assert len(fake_backend.span_trees) == 1

    assert_equal(EXPECTED_TRACE_TREE, fake_backend.trace_trees[0])


def test_broken_decorator__end_span_preprocessor__no_error(fake_backend):
    tracker_instance = BrokenOpikTrackDecorator(end_span_preprocessor_error=True)

    @tracker_instance.track
    def f_inner(num: int) -> int:
        return num

    f_inner(42)

    tracker_instance.flush_tracker()

    EXPECTED_TRACE_TREE = TraceModel(
        end_time=ANY_BUT_NONE,
        id=ANY_STRING(),
        input={"num": 42},
        name="f_inner",
        output={"output": 42},
        start_time=ANY_BUT_NONE,
        error_info=None,
        spans=[
            SpanModel(
                end_time=ANY_BUT_NONE,
                id=ANY_STRING(),
                input={"num": 42},
                name="f_inner",
                output={"output": 42},
                start_time=ANY_BUT_NONE,
                type="general",
                spans=[],
                error_info=None,
            ),
        ],
    )

    assert len(fake_backend.trace_trees) == 1
    assert len(fake_backend.span_trees) == 1

    assert_equal(EXPECTED_TRACE_TREE, fake_backend.trace_trees[0])
