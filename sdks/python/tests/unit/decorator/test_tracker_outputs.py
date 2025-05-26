import asyncio
import threading
from typing import Dict

from unittest import mock
import pytest

from opik import context_storage, opik_context
from opik.api_objects import opik_client, trace
from opik.decorator import tracker
from ...testlib import (
    ANY_BUT_NONE,
    ANY_STRING,
    FeedbackScoreModel,
    SpanModel,
    TraceModel,
    assert_equal,
)


def test_track__one_nested_function__happyflow(fake_backend):
    @tracker.track
    def f_inner(x):
        return "inner-output"

    @tracker.track
    def f_outer(x):
        f_inner("inner-input")
        return "outer-output"

    f_outer("outer-input")
    tracker.flush_tracker()

    EXPECTED_TRACE_TREE = TraceModel(
        id=ANY_BUT_NONE,
        name="f_outer",
        input={"x": "outer-input"},
        output={"output": "outer-output"},
        start_time=ANY_BUT_NONE,
        end_time=ANY_BUT_NONE,
        spans=[
            SpanModel(
                id=ANY_BUT_NONE,
                name="f_outer",
                input={"x": "outer-input"},
                output={"output": "outer-output"},
                start_time=ANY_BUT_NONE,
                end_time=ANY_BUT_NONE,
                spans=[
                    SpanModel(
                        id=ANY_BUT_NONE,
                        name="f_inner",
                        input={"x": "inner-input"},
                        output={"output": "inner-output"},
                        start_time=ANY_BUT_NONE,
                        end_time=ANY_BUT_NONE,
                        spans=[],
                    )
                ],
            )
        ],
    )

    assert len(fake_backend.trace_trees) == 1

    assert_equal(EXPECTED_TRACE_TREE, fake_backend.trace_trees[0])


def test_track__one_function_without_nesting__inputs_and_outputs_not_captured__inputs_and_outputs_initialized_with_Nones(
    fake_backend,
):
    @tracker.track(capture_output=False, capture_input=False)
    def f(x):
        return "the-output"

    f("the-input")
    tracker.flush_tracker()

    EXPECTED_TRACE_TREE = TraceModel(
        id=ANY_BUT_NONE,
        name="f",
        input=None,
        output=None,
        start_time=ANY_BUT_NONE,
        end_time=ANY_BUT_NONE,
        spans=[
            SpanModel(
                id=ANY_BUT_NONE,
                name="f",
                input=None,
                output=None,
                start_time=ANY_BUT_NONE,
                end_time=ANY_BUT_NONE,
                spans=[],
            )
        ],
    )

    assert len(fake_backend.trace_trees) == 1

    assert_equal(EXPECTED_TRACE_TREE, fake_backend.trace_trees[0])


def test_track__one_function_without_nesting__output_is_dict__output_is_wrapped_by_tracker(
    fake_backend,
):
    @tracker.track()
    def f(x):
        return {"some-key": "the-output-value"}

    f("the-input")
    tracker.flush_tracker()

    EXPECTED_TRACE_TREE = TraceModel(
        id=ANY_BUT_NONE,
        name="f",
        input={"x": "the-input"},
        output={"some-key": "the-output-value"},
        start_time=ANY_BUT_NONE,
        end_time=ANY_BUT_NONE,
        spans=[
            SpanModel(
                id=ANY_BUT_NONE,
                name="f",
                input={"x": "the-input"},
                output={"some-key": "the-output-value"},
                start_time=ANY_BUT_NONE,
                end_time=ANY_BUT_NONE,
                spans=[],
            )
        ],
    )

    assert len(fake_backend.trace_trees) == 1

    assert_equal(EXPECTED_TRACE_TREE, fake_backend.trace_trees[0])


def test_track__two_nested_functions__happyflow(fake_backend):
    @tracker.track
    def f_inner(z):
        return "inner-output"

    @tracker.track
    def f_middle(y):
        f_inner("inner-input")
        return "middle-output"

    @tracker.track
    def f_outer(x):
        f_middle("middle-input")
        return "outer-output"

    f_outer("outer-input")
    tracker.flush_tracker()

    EXPECTED_TRACE_TREE = TraceModel(
        id=ANY_BUT_NONE,
        name="f_outer",
        input={"x": "outer-input"},
        output={"output": "outer-output"},
        start_time=ANY_BUT_NONE,
        end_time=ANY_BUT_NONE,
        spans=[
            SpanModel(
                id=ANY_BUT_NONE,
                name="f_outer",
                input={"x": "outer-input"},
                output={"output": "outer-output"},
                start_time=ANY_BUT_NONE,
                end_time=ANY_BUT_NONE,
                spans=[
                    SpanModel(
                        id=ANY_BUT_NONE,
                        name="f_middle",
                        input={"y": "middle-input"},
                        output={"output": "middle-output"},
                        start_time=ANY_BUT_NONE,
                        end_time=ANY_BUT_NONE,
                        spans=[
                            SpanModel(
                                id=ANY_BUT_NONE,
                                name="f_inner",
                                input={"z": "inner-input"},
                                output={"output": "inner-output"},
                                start_time=ANY_BUT_NONE,
                                end_time=ANY_BUT_NONE,
                                spans=[],
                            )
                        ],
                    )
                ],
            )
        ],
    )

    assert len(fake_backend.trace_trees) == 1

    assert_equal(EXPECTED_TRACE_TREE, fake_backend.trace_trees[0])


def test_track__outer_function_has_two_separate_nested_function__happyflow(
    fake_backend,
):
    @tracker.track
    def f_inner_1(y):
        return "inner-output-1"

    @tracker.track
    def f_inner_2(y):
        return "inner-output-2"

    @tracker.track
    def f_outer(x):
        f_inner_1("inner-input-1")
        f_inner_2("inner-input-2")
        return "outer-output"

    f_outer("outer-input")
    tracker.flush_tracker()

    EXPECTED_TRACE_TREE = TraceModel(
        id=ANY_BUT_NONE,
        name="f_outer",
        input={"x": "outer-input"},
        output={"output": "outer-output"},
        start_time=ANY_BUT_NONE,
        end_time=ANY_BUT_NONE,
        spans=[
            SpanModel(
                id=ANY_BUT_NONE,
                name="f_outer",
                input={"x": "outer-input"},
                output={"output": "outer-output"},
                start_time=ANY_BUT_NONE,
                end_time=ANY_BUT_NONE,
                spans=[
                    SpanModel(
                        id=ANY_BUT_NONE,
                        name="f_inner_1",
                        input={"y": "inner-input-1"},
                        output={"output": "inner-output-1"},
                        start_time=ANY_BUT_NONE,
                        end_time=ANY_BUT_NONE,
                        spans=[],
                    ),
                    SpanModel(
                        id=ANY_BUT_NONE,
                        name="f_inner_2",
                        input={"y": "inner-input-2"},
                        output={"output": "inner-output-2"},
                        start_time=ANY_BUT_NONE,
                        end_time=ANY_BUT_NONE,
                        spans=[],
                    ),
                ],
            )
        ],
    )

    assert len(fake_backend.trace_trees) == 1

    assert_equal(EXPECTED_TRACE_TREE, fake_backend.trace_trees[0])


def test_track__two_traces__happyflow(fake_backend):
    @tracker.track
    def f_1(x):
        return "f1-output"

    @tracker.track
    def f_2(x):
        return "f2-output"

    f_1("f1-input")
    f_2("f2-input")

    tracker.flush_tracker()

    EXPECTED_TRACE_TREES = [
        TraceModel(
            id=ANY_BUT_NONE,
            name="f_1",
            input={"x": "f1-input"},
            output={"output": "f1-output"},
            start_time=ANY_BUT_NONE,
            end_time=ANY_BUT_NONE,
            spans=[
                SpanModel(
                    id=ANY_BUT_NONE,
                    name="f_1",
                    input={"x": "f1-input"},
                    output={"output": "f1-output"},
                    start_time=ANY_BUT_NONE,
                    end_time=ANY_BUT_NONE,
                    spans=[],
                )
            ],
        ),
        TraceModel(
            id=ANY_BUT_NONE,
            name="f_2",
            input={"x": "f2-input"},
            output={"output": "f2-output"},
            start_time=ANY_BUT_NONE,
            end_time=ANY_BUT_NONE,
            spans=[
                SpanModel(
                    id=ANY_BUT_NONE,
                    name="f_2",
                    input={"x": "f2-input"},
                    output={"output": "f2-output"},
                    start_time=ANY_BUT_NONE,
                    end_time=ANY_BUT_NONE,
                    spans=[],
                )
            ],
        ),
    ]

    assert len(fake_backend.trace_trees) == 2

    assert_equal(EXPECTED_TRACE_TREES[0], fake_backend.trace_trees[0])
    assert_equal(EXPECTED_TRACE_TREES[1], fake_backend.trace_trees[1])


def test_track__one_function__error_raised__trace_and_span_finished_correctly__outputs_are_None(
    fake_backend,
):
    @tracker.track
    def f(x):
        raise Exception("error message")

    with pytest.raises(Exception):
        f("the-input")

    tracker.flush_tracker()

    EXPECTED_TRACE_TREE = TraceModel(
        id=ANY_BUT_NONE,
        name="f",
        input={"x": "the-input"},
        output=None,
        start_time=ANY_BUT_NONE,
        end_time=ANY_BUT_NONE,
        error_info={
            "exception_type": "Exception",
            "message": "error message",
            "traceback": ANY_STRING(),
        },
        spans=[
            SpanModel(
                id=ANY_BUT_NONE,
                name="f",
                input={"x": "the-input"},
                output=None,
                start_time=ANY_BUT_NONE,
                end_time=ANY_BUT_NONE,
                error_info={
                    "exception_type": "Exception",
                    "message": "error message",
                    "traceback": ANY_STRING(),
                },
                spans=[],
            )
        ],
    )

    assert len(fake_backend.trace_trees) == 1

    assert_equal(EXPECTED_TRACE_TREE, fake_backend.trace_trees[0])


def test_track__nested_function__error_raised_in_inner_span_but_caught_in_outer_span__only_inner_span_has_error_info(
    fake_backend,
):
    @tracker.track
    def f(x):
        with pytest.raises(Exception):
            f_inner()

        return "the-output"

    @tracker.track
    def f_inner():
        raise Exception("error message")

    f("the-input")

    tracker.flush_tracker()

    EXPECTED_TRACE_TREE = TraceModel(
        id=ANY_BUT_NONE,
        name="f",
        input={"x": "the-input"},
        output={"output": "the-output"},
        start_time=ANY_BUT_NONE,
        end_time=ANY_BUT_NONE,
        spans=[
            SpanModel(
                id=ANY_BUT_NONE,
                name="f",
                input={"x": "the-input"},
                output={"output": "the-output"},
                start_time=ANY_BUT_NONE,
                end_time=ANY_BUT_NONE,
                spans=[
                    SpanModel(
                        id=ANY_BUT_NONE,
                        name="f_inner",
                        input={},
                        output=None,
                        start_time=ANY_BUT_NONE,
                        end_time=ANY_BUT_NONE,
                        error_info={
                            "exception_type": "Exception",
                            "message": "error message",
                            "traceback": ANY_STRING(),
                        },
                        spans=[],
                    )
                ],
            )
        ],
    )

    assert len(fake_backend.trace_trees) == 1

    assert_equal(EXPECTED_TRACE_TREE, fake_backend.trace_trees[0])


def test_track__one_async_function__error_raised__trace_and_span_finished_correctly__outputs_are_None__error_info_is_added(
    fake_backend,
):
    @tracker.track
    async def async_f(x):
        await asyncio.sleep(0.01)
        raise Exception("error message")

    with pytest.raises(Exception):
        asyncio.run(async_f("the-input"))

    tracker.flush_tracker()

    EXPECTED_TRACE_TREE = TraceModel(
        id=ANY_BUT_NONE,
        name="async_f",
        input={"x": "the-input"},
        output=None,
        start_time=ANY_BUT_NONE,
        end_time=ANY_BUT_NONE,
        error_info={
            "exception_type": "Exception",
            "message": "error message",
            "traceback": ANY_STRING(),
        },
        spans=[
            SpanModel(
                id=ANY_BUT_NONE,
                name="async_f",
                input={"x": "the-input"},
                output=None,
                start_time=ANY_BUT_NONE,
                end_time=ANY_BUT_NONE,
                error_info={
                    "exception_type": "Exception",
                    "message": "error message",
                    "traceback": ANY_STRING(),
                },
                spans=[],
            )
        ],
    )

    assert len(fake_backend.trace_trees) == 1

    assert_equal(EXPECTED_TRACE_TREE, fake_backend.trace_trees[0])


def test_track__nested_calls_in_separate_threads__3_traces_in_result(fake_backend):
    ID_STORAGE: Dict[str, str] = {}

    @tracker.track
    def f_inner(y, thread_id):
        ID_STORAGE[f"f_inner-trace-id-{thread_id}"] = (
            opik_context.get_current_trace_data().id
        )
        ID_STORAGE[f"f_inner-span-id-{thread_id}"] = (
            opik_context.get_current_span_data().id
        )
        return f"inner-output-from-{thread_id}"

    @tracker.track
    def f_outer(x):
        ID_STORAGE["f_outer-trace-id"] = opik_context.get_current_trace_data().id
        ID_STORAGE["f_outer-span-id"] = opik_context.get_current_span_data().id

        t1 = threading.Thread(target=f_inner, args=("inner-input-1", "thread-1"))
        t2 = threading.Thread(target=f_inner, args=("inner-input-2", "thread-2"))
        t1.start()
        t1.join()
        t2.start()
        t2.join()
        return "outer-output"

    f_outer("outer-input")

    tracker.flush_tracker()

    EXPECTED_TRACE_TREES = [
        TraceModel(
            id=ID_STORAGE["f_outer-trace-id"],
            name="f_outer",
            input={"x": "outer-input"},
            output={"output": "outer-output"},
            start_time=ANY_BUT_NONE,
            end_time=ANY_BUT_NONE,
            spans=[
                SpanModel(
                    id=ID_STORAGE["f_outer-span-id"],
                    name="f_outer",
                    input={"x": "outer-input"},
                    output={"output": "outer-output"},
                    start_time=ANY_BUT_NONE,
                    end_time=ANY_BUT_NONE,
                    spans=[],
                )
            ],
        ),
        TraceModel(
            id=ID_STORAGE["f_inner-trace-id-thread-1"],
            name="f_inner",
            input={"y": "inner-input-1", "thread_id": "thread-1"},
            output={"output": "inner-output-from-thread-1"},
            start_time=ANY_BUT_NONE,
            end_time=ANY_BUT_NONE,
            spans=[
                SpanModel(
                    id=ID_STORAGE["f_inner-span-id-thread-1"],
                    name="f_inner",
                    input={"y": "inner-input-1", "thread_id": "thread-1"},
                    output={"output": "inner-output-from-thread-1"},
                    start_time=ANY_BUT_NONE,
                    end_time=ANY_BUT_NONE,
                    spans=[],
                )
            ],
        ),
        TraceModel(
            id=ID_STORAGE["f_inner-trace-id-thread-2"],
            name="f_inner",
            input={"y": "inner-input-2", "thread_id": "thread-2"},
            output={"output": "inner-output-from-thread-2"},
            start_time=ANY_BUT_NONE,
            end_time=ANY_BUT_NONE,
            spans=[
                SpanModel(
                    id=ID_STORAGE["f_inner-span-id-thread-2"],
                    name="f_inner",
                    input={"y": "inner-input-2", "thread_id": "thread-2"},
                    output={"output": "inner-output-from-thread-2"},
                    start_time=ANY_BUT_NONE,
                    end_time=ANY_BUT_NONE,
                    spans=[],
                )
            ],
        ),
    ]

    assert len(fake_backend.trace_trees) == 3

    trace_outer = EXPECTED_TRACE_TREES[0]
    trace_inner_thread1 = EXPECTED_TRACE_TREES[1]
    trace_inner_thread2 = EXPECTED_TRACE_TREES[2]

    trace_backend_outer = [
        trace for trace in fake_backend.trace_trees if trace.id == trace_outer.id
    ][0]
    trace_backend_inner_thread1 = [
        trace
        for trace in fake_backend.trace_trees
        if trace.id == trace_inner_thread1.id
    ][0]
    trace_backend_inner_thread2 = [
        trace
        for trace in fake_backend.trace_trees
        if trace.id == trace_inner_thread2.id
    ][0]

    assert_equal(expected=trace_outer, actual=trace_backend_outer)
    assert_equal(expected=trace_inner_thread1, actual=trace_backend_inner_thread1)
    assert_equal(expected=trace_inner_thread2, actual=trace_backend_inner_thread2)


def test_track__single_generator_function_tracked__generator_exhausted__happyflow(
    fake_backend,
):
    @tracker.track
    def f(x):
        values = ["yielded-1", " yielded-2", " yielded-3"]
        for value in values:
            yield value

    generator = f("generator-input")
    for _ in generator:
        pass

    tracker.flush_tracker()

    EXPECTED_TRACE_TREE = TraceModel(
        id=ANY_BUT_NONE,
        name="f",
        input={"x": "generator-input"},
        output={"output": "yielded-1 yielded-2 yielded-3"},
        start_time=ANY_BUT_NONE,
        end_time=ANY_BUT_NONE,
        spans=[
            SpanModel(
                id=ANY_BUT_NONE,
                name="f",
                input={"x": "generator-input"},
                output={"output": "yielded-1 yielded-2 yielded-3"},
                start_time=ANY_BUT_NONE,
                end_time=ANY_BUT_NONE,
                spans=[],
            )
        ],
    )

    assert len(fake_backend.trace_trees) == 1

    assert_equal(EXPECTED_TRACE_TREE, fake_backend.trace_trees[0])


def test_track__single_generator_function_tracked__error_raised_during_the_generator_work__span_and_trace_finished_correctly__error_info_provided(
    fake_backend,
):
    @tracker.track
    def f(x):
        raise Exception("error message")
        yield

    generator = f("generator-input")

    with pytest.raises(Exception):
        for _ in generator:
            pass

    tracker.flush_tracker()

    EXPECTED_TRACE_TREE = TraceModel(
        id=ANY_BUT_NONE,
        name="f",
        input={"x": "generator-input"},
        output=None,
        start_time=ANY_BUT_NONE,
        end_time=ANY_BUT_NONE,
        error_info={
            "exception_type": "Exception",
            "message": "error message",
            "traceback": ANY_STRING(),
        },
        spans=[
            SpanModel(
                id=ANY_BUT_NONE,
                name="f",
                input={"x": "generator-input"},
                output=None,
                start_time=ANY_BUT_NONE,
                end_time=ANY_BUT_NONE,
                error_info={
                    "exception_type": "Exception",
                    "message": "error message",
                    "traceback": ANY_STRING(),
                },
                spans=[],
            )
        ],
    )

    assert len(fake_backend.trace_trees) == 1

    assert_equal(EXPECTED_TRACE_TREE, fake_backend.trace_trees[0])


def test_track__generator_function_tracked__generator_exhausted_in_another_tracked_function__generator_span_started_and_ended_with_generator_exhausting(
    fake_backend,
):
    @tracker.track
    def f_inner(z, generator):
        for _ in generator:
            pass

        return "inner-output"

    @tracker.track
    def gen_f(y):
        values = ["yielded-1", " yielded-2", " yielded-3"]
        for value in values:
            yield value

    @tracker.track
    def f_outer(x):
        generator = gen_f("generator-input")
        f_inner("inner-input", generator)
        return "outer-output"

    f_outer("outer-input")
    tracker.flush_tracker()

    EXPECTED_TRACE_TREE = TraceModel(
        id=ANY_BUT_NONE,
        name="f_outer",
        input={"x": "outer-input"},
        output={"output": "outer-output"},
        start_time=ANY_BUT_NONE,
        end_time=ANY_BUT_NONE,
        spans=[
            SpanModel(
                id=ANY_BUT_NONE,
                name="f_outer",
                input={"x": "outer-input"},
                output={"output": "outer-output"},
                start_time=ANY_BUT_NONE,
                end_time=ANY_BUT_NONE,
                spans=[
                    SpanModel(
                        id=ANY_BUT_NONE,
                        name="f_inner",
                        input={"z": "inner-input", "generator": ANY_BUT_NONE},
                        output={"output": "inner-output"},
                        start_time=ANY_BUT_NONE,
                        end_time=ANY_BUT_NONE,
                        spans=[
                            SpanModel(
                                id=ANY_BUT_NONE,
                                name="gen_f",
                                input={"y": "generator-input"},
                                output={"output": "yielded-1 yielded-2 yielded-3"},
                                start_time=ANY_BUT_NONE,
                                end_time=ANY_BUT_NONE,
                                spans=[],
                            ),
                        ],
                    ),
                ],
            ),
        ],
    )

    assert len(fake_backend.trace_trees) == 1

    assert_equal(EXPECTED_TRACE_TREE, fake_backend.trace_trees[0])


def test_track__generator_function_tracked__generator_exhausted_in_another_tracked_function__generator_span_started_and_ended_with_generator_exhausting__span_from_tracked_function_inside_generator_attached_to_generator_span(
    fake_backend,
):
    @tracker.track
    def f_inner(z, generator):
        for _ in generator:
            pass

        return "inner-output"

    @tracker.track
    def f_called_inside_generator():
        return "f-called-inside-generator-output"

    @tracker.track
    def gen_f(y):
        f_called_inside_generator()
        values = ["yielded-1", " yielded-2", " yielded-3"]
        for value in values:
            yield value

    @tracker.track
    def f_outer(x):
        generator = gen_f("generator-input")
        f_inner("inner-input", generator)
        return "outer-output"

    f_outer("outer-input")
    tracker.flush_tracker()

    EXPECTED_TRACE_TREE = TraceModel(
        id=ANY_BUT_NONE,
        name="f_outer",
        input={"x": "outer-input"},
        output={"output": "outer-output"},
        start_time=ANY_BUT_NONE,
        end_time=ANY_BUT_NONE,
        spans=[
            SpanModel(
                id=ANY_BUT_NONE,
                name="f_outer",
                input={"x": "outer-input"},
                output={"output": "outer-output"},
                start_time=ANY_BUT_NONE,
                end_time=ANY_BUT_NONE,
                spans=[
                    SpanModel(
                        id=ANY_BUT_NONE,
                        name="f_inner",
                        input={"z": "inner-input", "generator": ANY_BUT_NONE},
                        output={"output": "inner-output"},
                        start_time=ANY_BUT_NONE,
                        end_time=ANY_BUT_NONE,
                        spans=[
                            SpanModel(
                                id=ANY_BUT_NONE,
                                name="gen_f",
                                input={"y": "generator-input"},
                                output={"output": "yielded-1 yielded-2 yielded-3"},
                                start_time=ANY_BUT_NONE,
                                end_time=ANY_BUT_NONE,
                                spans=[
                                    SpanModel(
                                        id=ANY_BUT_NONE,
                                        name="f_called_inside_generator",
                                        input={},
                                        output={
                                            "output": "f-called-inside-generator-output"
                                        },
                                        start_time=ANY_BUT_NONE,
                                        end_time=ANY_BUT_NONE,
                                        spans=[],
                                    ),
                                ],
                            ),
                        ],
                    ),
                ],
            ),
        ],
    )

    assert len(fake_backend.trace_trees) == 1

    assert_equal(EXPECTED_TRACE_TREE, fake_backend.trace_trees[0])


def test_track__single_async_function_tracked__happyflow(
    fake_backend,
):
    @tracker.track
    async def async_f(x):
        await asyncio.sleep(0.01)
        return "the-output"

    assert asyncio.run(async_f("the-input")) == "the-output"

    tracker.flush_tracker()

    EXPECTED_TRACE_TREE = TraceModel(
        id=ANY_BUT_NONE,
        name="async_f",
        input={"x": "the-input"},
        output={"output": "the-output"},
        start_time=ANY_BUT_NONE,
        end_time=ANY_BUT_NONE,
        spans=[
            SpanModel(
                id=ANY_BUT_NONE,
                name="async_f",
                input={"x": "the-input"},
                output={"output": "the-output"},
                start_time=ANY_BUT_NONE,
                end_time=ANY_BUT_NONE,
                spans=[],
            )
        ],
    )

    assert len(fake_backend.trace_trees) == 1

    assert_equal(EXPECTED_TRACE_TREE, fake_backend.trace_trees[0])


def test_track__nested_async_function_tracked__happyflow(
    fake_backend,
):
    @tracker.track
    async def async_f_inner(y):
        await asyncio.sleep(0.01)
        return "inner-output"

    @tracker.track
    async def async_f_outer(x):
        await async_f_inner("inner-input")
        return "outer-output"

    assert asyncio.run(async_f_outer("outer-input")) == "outer-output"

    tracker.flush_tracker()

    EXPECTED_TRACE_TREE = TraceModel(
        id=ANY_BUT_NONE,
        name="async_f_outer",
        input={"x": "outer-input"},
        output={"output": "outer-output"},
        start_time=ANY_BUT_NONE,
        end_time=ANY_BUT_NONE,
        spans=[
            SpanModel(
                id=ANY_BUT_NONE,
                name="async_f_outer",
                input={"x": "outer-input"},
                output={"output": "outer-output"},
                start_time=ANY_BUT_NONE,
                end_time=ANY_BUT_NONE,
                spans=[
                    SpanModel(
                        id=ANY_BUT_NONE,
                        name="async_f_inner",
                        input={"y": "inner-input"},
                        output={"output": "inner-output"},
                        start_time=ANY_BUT_NONE,
                        end_time=ANY_BUT_NONE,
                        spans=[],
                    )
                ],
            )
        ],
    )

    assert len(fake_backend.trace_trees) == 1

    assert_equal(EXPECTED_TRACE_TREE, fake_backend.trace_trees[0])


def test_track__top_level_single_async_generator_function_tracked__generator_exhausted__happyflow(
    fake_backend,
):
    @tracker.track
    async def async_generator(x):
        await asyncio.sleep(0.01)

        for item in ["yielded-1", " yielded-2", " yielded-3"]:
            yield item

    async def async_generator_user():
        gen = async_generator("generator-input")
        async for _ in gen:
            pass

    asyncio.run(async_generator_user())

    tracker.flush_tracker()

    EXPECTED_TRACE_TREE = TraceModel(
        id=ANY_BUT_NONE,
        name="async_generator",
        input={"x": "generator-input"},
        output={"output": "yielded-1 yielded-2 yielded-3"},
        start_time=ANY_BUT_NONE,
        end_time=ANY_BUT_NONE,
        spans=[
            SpanModel(
                id=ANY_BUT_NONE,
                name="async_generator",
                input={"x": "generator-input"},
                output={"output": "yielded-1 yielded-2 yielded-3"},
                start_time=ANY_BUT_NONE,
                end_time=ANY_BUT_NONE,
                spans=[],
            )
        ],
    )

    assert len(fake_backend.trace_trees) == 1

    assert_equal(EXPECTED_TRACE_TREE, fake_backend.trace_trees[0])


def test_track__top_level_async_generator_function_tracked__generator_has_another_tracked_function_inside__nested_function_attached_to_generator_span_and_trace(
    fake_backend,
):
    @tracker.track
    async def some_async_work():
        await asyncio.sleep(0.001)

    @tracker.track
    async def async_generator(x):
        await some_async_work()

        for item in ["yielded-1", " yielded-2", " yielded-3"]:
            yield item

    async def async_generator_user():
        gen = async_generator("generator-input")
        async for _ in gen:
            pass

    asyncio.run(async_generator_user())

    tracker.flush_tracker()

    EXPECTED_TRACE_TREE = TraceModel(
        id=ANY_BUT_NONE,
        name="async_generator",
        input={"x": "generator-input"},
        output={"output": "yielded-1 yielded-2 yielded-3"},
        start_time=ANY_BUT_NONE,
        end_time=ANY_BUT_NONE,
        spans=[
            SpanModel(
                id=ANY_BUT_NONE,
                name="async_generator",
                input={"x": "generator-input"},
                output={"output": "yielded-1 yielded-2 yielded-3"},
                start_time=ANY_BUT_NONE,
                end_time=ANY_BUT_NONE,
                spans=[
                    SpanModel(
                        id=ANY_BUT_NONE,
                        name="some_async_work",
                        input={},
                        start_time=ANY_BUT_NONE,
                        end_time=ANY_BUT_NONE,
                        spans=[],
                    )
                ],
            )
        ],
    )

    assert len(fake_backend.trace_trees) == 1

    assert_equal(EXPECTED_TRACE_TREE, fake_backend.trace_trees[0])


def test_track__async_generator_inside_another_tracked_function__happyflow(
    fake_backend,
):
    @tracker.track
    async def async_generator(y):
        await asyncio.sleep(0.01)

        for item in ["yielded-1", " yielded-2", " yielded-3"]:
            yield item

    @tracker.track
    async def async_generator_user(x):
        async for _ in async_generator("generator-input"):
            pass

        return "generator-user-output"

    asyncio.run(async_generator_user("generator-user-input"))

    tracker.flush_tracker()

    EXPECTED_TRACE_TREE = TraceModel(
        id=ANY_BUT_NONE,
        name="async_generator_user",
        input={"x": "generator-user-input"},
        output={"output": "generator-user-output"},
        start_time=ANY_BUT_NONE,
        end_time=ANY_BUT_NONE,
        spans=[
            SpanModel(
                id=ANY_BUT_NONE,
                name="async_generator_user",
                input={"x": "generator-user-input"},
                output={"output": "generator-user-output"},
                start_time=ANY_BUT_NONE,
                end_time=ANY_BUT_NONE,
                spans=[
                    SpanModel(
                        id=ANY_BUT_NONE,
                        name="async_generator",
                        input={"y": "generator-input"},
                        output={"output": "yielded-1 yielded-2 yielded-3"},
                        start_time=ANY_BUT_NONE,
                        end_time=ANY_BUT_NONE,
                        spans=[],
                    )
                ],
            )
        ],
    )

    assert len(fake_backend.trace_trees) == 1

    assert_equal(EXPECTED_TRACE_TREE, fake_backend.trace_trees[0])


def test_track__async_generator_inside_another_tracked_function__another_tracked_function_called_inside_generator_and_attached_to_its_span(
    fake_backend,
):
    @tracker.track
    async def some_async_work():
        await asyncio.sleep(0.001)

    @tracker.track
    async def async_generator(y):
        await some_async_work()

        for item in ["yielded-1", " yielded-2", " yielded-3"]:
            yield item

    @tracker.track
    async def async_generator_user(x):
        async for _ in async_generator("generator-input"):
            pass

        return "generator-user-output"

    asyncio.run(async_generator_user("generator-user-input"))

    tracker.flush_tracker()

    EXPECTED_TRACE_TREE = TraceModel(
        id=ANY_BUT_NONE,
        name="async_generator_user",
        input={"x": "generator-user-input"},
        output={"output": "generator-user-output"},
        start_time=ANY_BUT_NONE,
        end_time=ANY_BUT_NONE,
        spans=[
            SpanModel(
                id=ANY_BUT_NONE,
                name="async_generator_user",
                input={"x": "generator-user-input"},
                output={"output": "generator-user-output"},
                start_time=ANY_BUT_NONE,
                end_time=ANY_BUT_NONE,
                spans=[
                    SpanModel(
                        id=ANY_BUT_NONE,
                        name="async_generator",
                        input={"y": "generator-input"},
                        output={"output": "yielded-1 yielded-2 yielded-3"},
                        start_time=ANY_BUT_NONE,
                        end_time=ANY_BUT_NONE,
                        spans=[
                            SpanModel(
                                id=ANY_BUT_NONE,
                                name="some_async_work",
                                input={},
                                output=None,
                                start_time=ANY_BUT_NONE,
                                end_time=ANY_BUT_NONE,
                                spans=[],
                            )
                        ],
                    )
                ],
            )
        ],
    )

    assert len(fake_backend.trace_trees) == 1

    assert_equal(EXPECTED_TRACE_TREE, fake_backend.trace_trees[0])


def test_track__distributed_tracing_with_headers__tracing_is_performed_in_2_threads__all_data_is_saved_in_1_trace_tree(
    fake_backend,
):
    @tracker.track
    def f_remote(y, thread_id):
        return f"f-remote-output-from-{thread_id}"

    def distributed_node_runner(y, thread_id, opik_headers):
        f_remote(y, thread_id, opik_distributed_trace_headers=opik_headers)
        return "result-from-node-runner"

    @tracker.track
    def f_outer(x):
        distributed_trace_headers = opik_context.get_distributed_trace_headers()
        t1 = threading.Thread(
            target=distributed_node_runner,
            args=("remote-input-1", "thread-1", distributed_trace_headers),
        )
        t1.start()
        t1.join()
        return "outer-output"

    f_outer("outer-input")

    tracker.flush_tracker()

    EXPECTED_TRACE_TREE = TraceModel(
        id=ANY_BUT_NONE,
        name="f_outer",
        input={"x": "outer-input"},
        output={"output": "outer-output"},
        start_time=ANY_BUT_NONE,
        end_time=ANY_BUT_NONE,
        spans=[
            SpanModel(
                id=ANY_BUT_NONE,
                name="f_outer",
                input={"x": "outer-input"},
                output={"output": "outer-output"},
                start_time=ANY_BUT_NONE,
                end_time=ANY_BUT_NONE,
                spans=[
                    SpanModel(
                        id=ANY_BUT_NONE,
                        name="f_remote",
                        input={"y": "remote-input-1", "thread_id": "thread-1"},
                        output={"output": "f-remote-output-from-thread-1"},
                        start_time=ANY_BUT_NONE,
                        end_time=ANY_BUT_NONE,
                        spans=[],
                    )
                ],
            )
        ],
    )

    assert len(fake_backend.trace_trees) == 1

    assert_equal(EXPECTED_TRACE_TREE, fake_backend.trace_trees[0])


def test_track__trace_already_created_not_by_decorator__decorator_just_attaches_new_span_to_it__trace_is_not_popped_from_context_in_the_end(
    fake_backend,
):
    @tracker.track
    def f(x):
        return "f-output"

    client = opik_client.get_client_cached()
    trace_data = trace.TraceData(
        id="manually-created-trace-id",
        name="manually-created-trace",
        input={"input": "input-of-manually-created-trace"},
    )
    context_storage.set_trace_data(trace_data)

    f("f-input")

    context_storage.pop_trace_data()

    # Send create-trace message manually
    client.trace(
        id="manually-created-trace-id",
        name="manually-created-trace",
        input={"input": "input-of-manually-created-trace"},
        output={"output": "output-of-manually-created-trace"},
    )

    tracker.flush_tracker()

    EXPECTED_TRACE_TREE = TraceModel(
        id=ANY_BUT_NONE,
        name="manually-created-trace",
        input={"input": "input-of-manually-created-trace"},
        output={"output": "output-of-manually-created-trace"},
        start_time=mock.ANY,  # not ANY_BUT_NONE because we created span manually in the test
        end_time=mock.ANY,
        spans=[
            SpanModel(
                id=ANY_BUT_NONE,
                name="f",
                input={"x": "f-input"},
                output={"output": "f-output"},
                start_time=ANY_BUT_NONE,
                end_time=ANY_BUT_NONE,
            )
        ],
    )

    assert len(fake_backend.trace_trees) == 1

    assert_equal(EXPECTED_TRACE_TREE, fake_backend.trace_trees[0])


def test_track__span_and_trace_updated_via_opik_context(fake_backend):
    @tracker.track
    def f(x):
        opik_context.update_current_span(
            name="span-name",
            metadata={"span-metadata-key": "span-metadata-value"},
            total_cost=0.42,
            model="gpt-3.5-turbo",
            provider="openai",
        )
        opik_context.update_current_trace(
            name="trace-name",
            metadata={"trace-metadata-key": "trace-metadata-value"},
            thread_id="some-thread-id",
        )

        return "f-output"

    f("f-input")
    tracker.flush_tracker()

    EXPECTED_TRACE_TREE = TraceModel(
        id=ANY_BUT_NONE,
        name="trace-name",
        input={"x": "f-input"},
        metadata={"trace-metadata-key": "trace-metadata-value"},
        output={"output": "f-output"},
        start_time=ANY_BUT_NONE,
        end_time=ANY_BUT_NONE,
        thread_id="some-thread-id",
        spans=[
            SpanModel(
                id=ANY_BUT_NONE,
                name="span-name",
                input={"x": "f-input"},
                metadata={"span-metadata-key": "span-metadata-value"},
                output={"output": "f-output"},
                start_time=ANY_BUT_NONE,
                end_time=ANY_BUT_NONE,
                total_cost=0.42,
                spans=[],
                model="gpt-3.5-turbo",
                provider="openai",
            )
        ],
    )

    assert len(fake_backend.trace_trees) == 1

    assert_equal(EXPECTED_TRACE_TREE, fake_backend.trace_trees[0])


def test_track__span_and_trace_input_output_updated_via_opik_context(fake_backend):
    @tracker.track
    def f(x):
        opik_context.update_current_span(
            input={"span-input-key": "span-input-value"},
            output={"span-output-key": "span-output-value"},
        )
        opik_context.update_current_trace(
            input={"trace-input-key": "trace-input-value"},
            output={"trace-output-key": "trace-output-value"},
        )

        return "f-output"

    f("f-input")
    tracker.flush_tracker()

    EXPECTED_TRACE_TREE = TraceModel(
        id=ANY_BUT_NONE,
        name="f",
        input={"x": "f-input", "trace-input-key": "trace-input-value"},
        output={"output": "f-output", "trace-output-key": "trace-output-value"},
        start_time=ANY_BUT_NONE,
        end_time=ANY_BUT_NONE,
        spans=[
            SpanModel(
                id=ANY_BUT_NONE,
                name="f",
                input={"x": "f-input", "span-input-key": "span-input-value"},
                output={"output": "f-output", "span-output-key": "span-output-value"},
                start_time=ANY_BUT_NONE,
                end_time=ANY_BUT_NONE,
                spans=[],
            )
        ],
    )

    assert len(fake_backend.trace_trees) == 1

    assert_equal(EXPECTED_TRACE_TREE, fake_backend.trace_trees[0])


def test_track__span_and_trace_updated_via_opik_context_with_feedback_scores__feedback_scores_are_also_logged(
    fake_backend,
):
    @tracker.track
    def f(x):
        opik_context.update_current_span(
            name="span-name",
            feedback_scores=[{"name": "span-score-name", "value": 0.5}],
        )
        opik_context.update_current_trace(
            name="trace-name",
            feedback_scores=[{"name": "trace-score-name", "value": 0.75}],
        )

        return "f-output"

    f("f-input")
    tracker.flush_tracker()

    EXPECTED_TRACE_TREE = TraceModel(
        id=ANY_BUT_NONE,
        name="trace-name",
        input={"x": "f-input"},
        output={"output": "f-output"},
        start_time=ANY_BUT_NONE,
        end_time=ANY_BUT_NONE,
        feedback_scores=[
            FeedbackScoreModel(id=ANY_BUT_NONE, name="trace-score-name", value=0.75)
        ],
        spans=[
            SpanModel(
                id=ANY_BUT_NONE,
                name="span-name",
                input={"x": "f-input"},
                output={"output": "f-output"},
                start_time=ANY_BUT_NONE,
                end_time=ANY_BUT_NONE,
                spans=[],
                feedback_scores=[
                    FeedbackScoreModel(
                        id=ANY_BUT_NONE, name="span-score-name", value=0.5
                    )
                ],
            )
        ],
    )

    assert len(fake_backend.trace_trees) == 1

    assert_equal(EXPECTED_TRACE_TREE, fake_backend.trace_trees[0])


def test_tracker__ignore_list_was_passed__ignored_inputs_are_not_logged(fake_backend):
    @tracker.track(ignore_arguments=["a", "c", "e", "unknown_argument"])
    def f(a, b, c=3, d=4, e=5):
        return {"some-key": "the-output-value"}

    f(1, 2)
    tracker.flush_tracker()

    EXPECTED_TRACE_TREE = TraceModel(
        id=ANY_BUT_NONE,
        name="f",
        input={"b": 2, "d": 4},
        output={"some-key": "the-output-value"},
        start_time=ANY_BUT_NONE,
        end_time=ANY_BUT_NONE,
        spans=[
            SpanModel(
                id=ANY_BUT_NONE,
                name="f",
                input={"b": 2, "d": 4},
                output={"some-key": "the-output-value"},
                start_time=ANY_BUT_NONE,
                end_time=ANY_BUT_NONE,
                spans=[],
            )
        ],
    )

    assert len(fake_backend.trace_trees) == 1
    assert_equal(EXPECTED_TRACE_TREE, fake_backend.trace_trees[0])


def test_tracker__ignore_list_was_passed__function_does_not_have_any_arguments__input_dicts_are_empty(
    fake_backend,
):
    @tracker.track(ignore_arguments=["a", "c", "e", "unknown_argument"])
    def f():
        return {"some-key": "the-output-value"}

    f()
    tracker.flush_tracker()

    EXPECTED_TRACE_TREE = TraceModel(
        id=ANY_BUT_NONE,
        name="f",
        input={},
        output={"some-key": "the-output-value"},
        start_time=ANY_BUT_NONE,
        end_time=ANY_BUT_NONE,
        spans=[
            SpanModel(
                id=ANY_BUT_NONE,
                name="f",
                input={},
                output={"some-key": "the-output-value"},
                start_time=ANY_BUT_NONE,
                end_time=ANY_BUT_NONE,
                spans=[],
            )
        ],
    )

    assert len(fake_backend.trace_trees) == 1
    assert_equal(EXPECTED_TRACE_TREE, fake_backend.trace_trees[0])


def test_track__function_called_with_wrong_arguments__trace_is_still_created_with_attached_type_error__inputs_captured_in_another_format(
    fake_backend,
):
    @tracker.track
    def f(x):
        return "the-output"

    with pytest.raises(TypeError):
        f(y=5)

    tracker.flush_tracker()

    EXPECTED_TRACE_TREE = TraceModel(
        id=ANY_BUT_NONE,
        name="f",
        input={"args": list(), "kwargs": {"y": 5}},
        output=None,
        start_time=ANY_BUT_NONE,
        end_time=ANY_BUT_NONE,
        error_info={
            "exception_type": "TypeError",
            "traceback": ANY_STRING(),
            "message": ANY_STRING(),
        },
        spans=[
            SpanModel(
                id=ANY_BUT_NONE,
                name="f",
                input={"args": list(), "kwargs": {"y": 5}},
                output=None,
                start_time=ANY_BUT_NONE,
                end_time=ANY_BUT_NONE,
                error_info={
                    "exception_type": "TypeError",
                    "traceback": ANY_STRING(),
                    "message": ANY_STRING(),
                },
                spans=[],
            )
        ],
    )

    assert len(fake_backend.trace_trees) == 1

    assert_equal(EXPECTED_TRACE_TREE, fake_backend.trace_trees[0])


def test_track__span_usage_updated__openai_format(fake_backend):
    @tracker.track
    def f(x):
        opik_context.update_current_span(
            usage={
                "completion_tokens": 10,
                "prompt_tokens": 20,
                "total_tokens": 30,
            },
            provider="openai",
        )

        return "f-output"

    f("f-input")
    tracker.flush_tracker()

    EXPECTED_TRACE_TREE = TraceModel(
        id=ANY_BUT_NONE,
        name="f",
        input={"x": "f-input"},
        output={"output": "f-output"},
        start_time=ANY_BUT_NONE,
        end_time=ANY_BUT_NONE,
        spans=[
            SpanModel(
                id=ANY_BUT_NONE,
                name="f",
                input={"x": "f-input"},
                output={"output": "f-output"},
                start_time=ANY_BUT_NONE,
                end_time=ANY_BUT_NONE,
                spans=[],
                provider="openai",
                usage={
                    "completion_tokens": 10,
                    "prompt_tokens": 20,
                    "total_tokens": 30,
                    "original_usage.completion_tokens": 10,
                    "original_usage.prompt_tokens": 20,
                    "original_usage.total_tokens": 30,
                },
            )
        ],
    )

    assert len(fake_backend.trace_trees) == 1

    assert_equal(EXPECTED_TRACE_TREE, fake_backend.trace_trees[0])


def test_track__function_called_with_mutable_input_which_changed_afterward__check_span_and_trace_inputs_are_not_affected(
    fake_backend,
):
    @tracker.track
    def f(x):
        return "the-output"

    messages = [
        {"role": "system", "content": "You are a helpful assistant."},
        {"role": "user", "content": "Hello"},
    ]
    f(messages)

    # mutate input data to see if it affects the trace and spans input's created
    messages.append(
        {
            "unrelated": "unrelated",
        }
    )
    tracker.flush_tracker()

    EXPECTED_TRACE_TREE = TraceModel(
        id=ANY_BUT_NONE,
        name="f",
        input={
            "x": [
                {"content": "You are a helpful assistant.", "role": "system"},
                {"content": "Hello", "role": "user"},
            ]
        },
        output={"output": "the-output"},
        start_time=ANY_BUT_NONE,
        end_time=ANY_BUT_NONE,
        spans=[
            SpanModel(
                id=ANY_BUT_NONE,
                name="f",
                input={
                    "x": [
                        {"content": "You are a helpful assistant.", "role": "system"},
                        {"content": "Hello", "role": "user"},
                    ]
                },
                output={"output": "the-output"},
                start_time=ANY_BUT_NONE,
                end_time=ANY_BUT_NONE,
            )
        ],
    )

    assert len(fake_backend.trace_trees) == 1
    trace_tree = fake_backend.trace_trees[0]

    assert_equal(EXPECTED_TRACE_TREE, trace_tree)
