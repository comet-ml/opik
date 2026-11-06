from typing import Any, Callable, Dict, List, Optional, Tuple
from unittest import mock

import pytest
from typing_extensions import override

from opik import context_storage, opik_context
from opik.api_objects import opik_client, span
from opik.decorator import (
    arguments_helpers,
    base_track_decorator,
    inspect_helpers,
    tracker,
)
from ...testlib import ANY_BUT_NONE, ANY_STRING, SpanModel, TraceModel, assert_equal


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
        opik_ = opik_client.get_global_client()
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
        id=ANY_STRING,
        input=None,
        name="f_inner",
        output={"output": 42},
        start_time=ANY_BUT_NONE,
        last_updated_at=ANY_BUT_NONE,
        error_info=None,
        spans=[
            SpanModel(
                end_time=ANY_BUT_NONE,
                id=ANY_STRING,
                input=None,
                name="f_inner",
                output={"output": 42},
                start_time=ANY_BUT_NONE,
                type="general",
                spans=[],
                error_info=None,
                source="sdk",
            ),
        ],
        source="sdk",
    )

    assert len(fake_backend.trace_trees) == 1
    assert len(fake_backend.span_trees) == 1

    assert_equal(expected=EXPECTED_TRACE_TREE, actual=fake_backend.trace_trees[0])


def test_broken_decorator__end_span_preprocessor__no_error(fake_backend):
    tracker_instance = BrokenOpikTrackDecorator(end_span_preprocessor_error=True)

    @tracker_instance.track
    def f_inner(num: int) -> int:
        return num

    f_inner(42)

    tracker_instance.flush_tracker()

    EXPECTED_TRACE_TREE = TraceModel(
        end_time=ANY_BUT_NONE,
        id=ANY_STRING,
        input={"num": 42},
        name="f_inner",
        output={"output": 42},
        start_time=ANY_BUT_NONE,
        last_updated_at=ANY_BUT_NONE,
        error_info=None,
        spans=[
            SpanModel(
                end_time=ANY_BUT_NONE,
                id=ANY_STRING,
                input={"num": 42},
                name="f_inner",
                output={"output": 42},
                start_time=ANY_BUT_NONE,
                type="general",
                spans=[],
                error_info=None,
                source="sdk",
            ),
        ],
        source="sdk",
    )

    assert len(fake_backend.trace_trees) == 1
    assert len(fake_backend.span_trees) == 1

    assert_equal(expected=EXPECTED_TRACE_TREE, actual=fake_backend.trace_trees[0])


def _fail_first_call(method_name: str):
    original = getattr(opik_client.Opik, method_name)
    calls = []

    def side_effect(self, **kwargs):
        calls.append(kwargs)
        if len(calls) == 1:
            raise RuntimeError("boom")
        return original(self, **kwargs)

    return mock.patch.object(opik_client.Opik, method_name, side_effect)


@pytest.mark.parametrize(
    "failing_start_message,create_duplicate_root_span",
    [
        ("__internal_api__span__", True),
        ("__internal_api__trace__", True),
        ("__internal_api__trace__", False),
    ],
)
def test_track__start_message_fails__call_logged_and_next_call_gets_own_trace(
    fake_backend, caplog, failing_start_message, create_duplicate_root_span
):
    @tracker.track(create_duplicate_root_span=create_duplicate_root_span)
    def f(x):
        return x

    with _fail_first_call(failing_start_message):
        assert f(1) == 1

    assert "log the start of f" in caplog.text

    assert context_storage.top_span_data() is None
    assert context_storage.get_trace_data() is None

    f(2)
    tracker.flush_tracker()

    EXPECTED_TRACE_TREES = [
        TraceModel(
            id=ANY_STRING,
            name="f",
            input={"x": x},
            output={"output": x},
            start_time=ANY_BUT_NONE,
            end_time=ANY_BUT_NONE,
            last_updated_at=ANY_BUT_NONE,
            spans=(
                [
                    SpanModel(
                        id=ANY_STRING,
                        name="f",
                        input={"x": x},
                        output={"output": x},
                        start_time=ANY_BUT_NONE,
                        end_time=ANY_BUT_NONE,
                        type="general",
                        spans=[],
                        source="sdk",
                    )
                ]
                if create_duplicate_root_span
                else []
            ),
        )
        for x in (1, 2)
    ]

    assert len(fake_backend.trace_trees) == 2
    for expected, actual in zip(EXPECTED_TRACE_TREES, fake_backend.trace_trees):
        assert_equal(expected=expected, actual=actual)


def test_track__start_message_fails__current_span_still_updatable(fake_backend):
    @tracker.track
    def f():
        opik_context.update_current_span(metadata={"k": 1})
        return 1

    with _fail_first_call("__internal_api__span__"):
        assert f() == 1
    tracker.flush_tracker()

    assert len(fake_backend.trace_trees) == 1
    assert fake_backend.trace_trees[0].spans[0].metadata == {"k": 1}


def test_track__nested_start_message_fails__inner_span_logged_under_outer(
    fake_backend,
):
    @tracker.track
    def inner(x):
        return x

    @tracker.track
    def outer(x):
        with _fail_first_call("__internal_api__span__"):
            inner(x)
        return context_storage.top_span_data().name

    assert outer(1) == "outer"
    tracker.flush_tracker()

    assert len(fake_backend.trace_trees) == 1
    outer_span = fake_backend.trace_trees[0].spans[0]
    assert outer_span.name == "outer"
    assert [s.name for s in outer_span.spans] == ["inner"]


def test_track__self_referencing_input__span_logged_and_next_call_gets_own_trace(
    fake_backend,
):
    @tracker.track
    def f(x):
        return "ok"

    cyclic = {"a": 1}
    cyclic["self"] = cyclic

    f(cyclic)
    f({"b": 2})
    tracker.flush_tracker()

    assert len(fake_backend.trace_trees) == 2
    assert fake_backend.trace_trees[0].spans[0].input == {
        "x": {"a": 1, "self": f"<Cyclic reference to dict id={id(cyclic)}>"}
    }
    assert fake_backend.trace_trees[1].input == {"x": {"b": 2}}
