import mock
import threading
import asyncio
import pytest
from opik.message_processing import streamer_constructors
from opik.decorator import tracker
from opik import context_storage, opik_context
from opik.api_objects import opik_client
from opik.api_objects import trace

from ...testlib import backend_emulator_message_processor
from ...testlib import (
    SpanModel,
    TraceModel,
    FeedbackScoreModel,
    ANY_BUT_NONE,
    assert_equal,
)


def test_track__one_nested_function__happyflow(fake_streamer):
    fake_message_processor_: (
        backend_emulator_message_processor.BackendEmulatorMessageProcessor
    )
    streamer, fake_message_processor_ = fake_streamer

    mock_construct_online_streamer = mock.Mock()
    mock_construct_online_streamer.return_value = streamer

    with mock.patch.object(
        streamer_constructors,
        "construct_online_streamer",
        mock_construct_online_streamer,
    ):

        @tracker.track(capture_output=True)
        def f_inner(x):
            return "inner-output"

        @tracker.track(capture_output=True)
        def f_outer(x):
            f_inner("inner-input")
            return "outer-output"

        f_outer("outer-input")
        tracker.flush_tracker()
        mock_construct_online_streamer.assert_called_once()

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

        assert len(fake_message_processor_.trace_trees) == 1

        assert_equal(EXPECTED_TRACE_TREE, fake_message_processor_.trace_trees[0])


def test_track__one_function_without_nesting__inputs_and_outputs_not_captured__inputs_and_outputs_initialized_with_Nones(
    fake_streamer,
):
    fake_message_processor_: (
        backend_emulator_message_processor.BackendEmulatorMessageProcessor
    )
    streamer, fake_message_processor_ = fake_streamer

    mock_construct_online_streamer = mock.Mock()
    mock_construct_online_streamer.return_value = streamer

    with mock.patch.object(
        streamer_constructors,
        "construct_online_streamer",
        mock_construct_online_streamer,
    ):

        @tracker.track(capture_output=False, capture_input=False)
        def f(x):
            return "the-output"

        f("the-input")
        tracker.flush_tracker()
        mock_construct_online_streamer.assert_called_once()

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

        assert len(fake_message_processor_.trace_trees) == 1

        assert_equal(EXPECTED_TRACE_TREE, fake_message_processor_.trace_trees[0])


def test_track__one_function_without_nesting__output_is_dict__output_is_wrapped_by_tracker(
    fake_streamer,
):
    fake_message_processor_: (
        backend_emulator_message_processor.BackendEmulatorMessageProcessor
    )
    streamer, fake_message_processor_ = fake_streamer

    mock_construct_online_streamer = mock.Mock()
    mock_construct_online_streamer.return_value = streamer

    with mock.patch.object(
        streamer_constructors,
        "construct_online_streamer",
        mock_construct_online_streamer,
    ):

        @tracker.track()
        def f(x):
            return {"some-key": "the-output-value"}

        f("the-input")
        tracker.flush_tracker()
        mock_construct_online_streamer.assert_called_once()

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

        assert len(fake_message_processor_.trace_trees) == 1

        assert_equal(EXPECTED_TRACE_TREE, fake_message_processor_.trace_trees[0])


def test_track__two_nested_functions__happyflow(fake_streamer):
    fake_message_processor_: (
        backend_emulator_message_processor.BackendEmulatorMessageProcessor
    )
    streamer, fake_message_processor_ = fake_streamer

    mock_construct_online_streamer = mock.Mock()
    mock_construct_online_streamer.return_value = streamer

    with mock.patch.object(
        streamer_constructors,
        "construct_online_streamer",
        mock_construct_online_streamer,
    ):

        @tracker.track(capture_output=True)
        def f_inner(z):
            return "inner-output"

        @tracker.track(capture_output=True)
        def f_middle(y):
            f_inner("inner-input")
            return "middle-output"

        @tracker.track(capture_output=True)
        def f_outer(x):
            f_middle("middle-input")
            return "outer-output"

        f_outer("outer-input")
        tracker.flush_tracker()
        mock_construct_online_streamer.assert_called_once()

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

        assert len(fake_message_processor_.trace_trees) == 1

        assert_equal(EXPECTED_TRACE_TREE, fake_message_processor_.trace_trees[0])


def test_track__outer_function_has_two_separate_nested_function__happyflow(
    fake_streamer,
):
    fake_message_processor_: (
        backend_emulator_message_processor.BackendEmulatorMessageProcessor
    )
    streamer, fake_message_processor_ = fake_streamer

    mock_construct_online_streamer = mock.Mock()
    mock_construct_online_streamer.return_value = streamer

    with mock.patch.object(
        streamer_constructors,
        "construct_online_streamer",
        mock_construct_online_streamer,
    ):

        @tracker.track(capture_output=True)
        def f_inner_1(y):
            return "inner-output-1"

        @tracker.track(capture_output=True)
        def f_inner_2(y):
            return "inner-output-2"

        @tracker.track(capture_output=True)
        def f_outer(x):
            f_inner_1("inner-input-1")
            f_inner_2("inner-input-2")
            return "outer-output"

        f_outer("outer-input")
        tracker.flush_tracker()
        mock_construct_online_streamer.assert_called_once()

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

        assert len(fake_message_processor_.trace_trees) == 1

        assert_equal(EXPECTED_TRACE_TREE, fake_message_processor_.trace_trees[0])


def test_track__two_traces__happyflow(fake_streamer):
    fake_message_processor_: (
        backend_emulator_message_processor.BackendEmulatorMessageProcessor
    )
    streamer, fake_message_processor_ = fake_streamer

    mock_construct_online_streamer = mock.Mock()
    mock_construct_online_streamer.return_value = streamer

    with mock.patch.object(
        streamer_constructors,
        "construct_online_streamer",
        mock_construct_online_streamer,
    ):

        @tracker.track(capture_output=True)
        def f_1(x):
            return "f1-output"

        @tracker.track(capture_output=True)
        def f_2(x):
            return "f2-output"

        f_1("f1-input")
        f_2("f2-input")

        tracker.flush_tracker()
        mock_construct_online_streamer.assert_called_once()

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

        assert len(fake_message_processor_.trace_trees) == 2

        assert_equal(EXPECTED_TRACE_TREES[0], fake_message_processor_.trace_trees[0])
        assert_equal(EXPECTED_TRACE_TREES[1], fake_message_processor_.trace_trees[1])


def test_track__one_function__error_raised__trace_and_span_finished_correctly__outputs_are_None(
    fake_streamer,
):
    fake_message_processor_: (
        backend_emulator_message_processor.BackendEmulatorMessageProcessor
    )
    streamer, fake_message_processor_ = fake_streamer

    mock_construct_online_streamer = mock.Mock()
    mock_construct_online_streamer.return_value = streamer

    with mock.patch.object(
        streamer_constructors,
        "construct_online_streamer",
        mock_construct_online_streamer,
    ):

        @tracker.track(capture_output=True)
        def f(x):
            raise Exception

        with pytest.raises(Exception):
            f("the-input")

        tracker.flush_tracker()
        mock_construct_online_streamer.assert_called_once()

        EXPECTED_TRACE_TREE = TraceModel(
            id=ANY_BUT_NONE,
            name="f",
            input={"x": "the-input"},
            output=None,
            start_time=ANY_BUT_NONE,
            end_time=ANY_BUT_NONE,
            spans=[
                SpanModel(
                    id=ANY_BUT_NONE,
                    name="f",
                    input={"x": "the-input"},
                    output=None,
                    start_time=ANY_BUT_NONE,
                    end_time=ANY_BUT_NONE,
                    spans=[],
                )
            ],
        )

        assert len(fake_message_processor_.trace_trees) == 1

        assert_equal(EXPECTED_TRACE_TREE, fake_message_processor_.trace_trees[0])


def test_track__one_async_function__error_raised__trace_and_span_finished_correctly__outputs_are_None(
    fake_streamer,
):
    fake_message_processor_: (
        backend_emulator_message_processor.BackendEmulatorMessageProcessor
    )
    streamer, fake_message_processor_ = fake_streamer

    mock_construct_online_streamer = mock.Mock()
    mock_construct_online_streamer.return_value = streamer

    with mock.patch.object(
        streamer_constructors,
        "construct_online_streamer",
        mock_construct_online_streamer,
    ):

        @tracker.track(capture_output=True)
        async def async_f(x):
            await asyncio.sleep(0.01)
            raise Exception

        with pytest.raises(Exception):
            asyncio.run(async_f("the-input"))

        tracker.flush_tracker()
        mock_construct_online_streamer.assert_called_once()

        EXPECTED_TRACE_TREE = TraceModel(
            id=ANY_BUT_NONE,
            name="async_f",
            input={"x": "the-input"},
            output=None,
            start_time=ANY_BUT_NONE,
            end_time=ANY_BUT_NONE,
            spans=[
                SpanModel(
                    id=ANY_BUT_NONE,
                    name="async_f",
                    input={"x": "the-input"},
                    output=None,
                    start_time=ANY_BUT_NONE,
                    end_time=ANY_BUT_NONE,
                    spans=[],
                )
            ],
        )

        assert len(fake_message_processor_.trace_trees) == 1

        assert_equal(EXPECTED_TRACE_TREE, fake_message_processor_.trace_trees[0])


def test_track__nested_calls_in_separate_threads__3_traces_in_result(fake_streamer):
    fake_message_processor_: (
        backend_emulator_message_processor.BackendEmulatorMessageProcessor
    )
    streamer, fake_message_processor_ = fake_streamer

    mock_construct_online_streamer = mock.Mock()
    mock_construct_online_streamer.return_value = streamer

    with mock.patch.object(
        streamer_constructors,
        "construct_online_streamer",
        mock_construct_online_streamer,
    ):

        @tracker.track(capture_output=True)
        def f_inner(y, thread_id):
            return f"inner-output-from-{thread_id}"

        @tracker.track(capture_output=True)
        def f_outer(x):
            t1 = threading.Thread(target=f_inner, args=("inner-input-1", "thread-1"))
            t2 = threading.Thread(target=f_inner, args=("inner-input-2", "thread-2"))
            t1.start()
            t1.join()
            t2.start()
            t2.join()
            return "outer-output"

        f_outer("outer-input")

        tracker.flush_tracker()
        mock_construct_online_streamer.assert_called_once()

        EXPECTED_TRACE_TREES = [
            TraceModel(
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
                        spans=[],
                    )
                ],
            ),
            TraceModel(
                id=ANY_BUT_NONE,
                name="f_inner",
                input={"y": "inner-input-1", "thread_id": "thread-1"},
                output={"output": "inner-output-from-thread-1"},
                start_time=ANY_BUT_NONE,
                end_time=ANY_BUT_NONE,
                spans=[
                    SpanModel(
                        id=ANY_BUT_NONE,
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
                id=ANY_BUT_NONE,
                name="f_inner",
                input={"y": "inner-input-2", "thread_id": "thread-2"},
                output={"output": "inner-output-from-thread-2"},
                start_time=ANY_BUT_NONE,
                end_time=ANY_BUT_NONE,
                spans=[
                    SpanModel(
                        id=ANY_BUT_NONE,
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

        assert len(fake_message_processor_.trace_trees) == 3

        assert_equal(EXPECTED_TRACE_TREES[0], fake_message_processor_.trace_trees[0])
        assert_equal(EXPECTED_TRACE_TREES[1], fake_message_processor_.trace_trees[1])
        assert_equal(EXPECTED_TRACE_TREES[2], fake_message_processor_.trace_trees[2])


def test_track__single_generator_function_tracked__generator_exhausted__happyflow(
    fake_streamer,
):
    fake_message_processor_: (
        backend_emulator_message_processor.BackendEmulatorMessageProcessor
    )
    streamer, fake_message_processor_ = fake_streamer

    mock_construct_online_streamer = mock.Mock()
    mock_construct_online_streamer.return_value = streamer

    with mock.patch.object(
        streamer_constructors,
        "construct_online_streamer",
        mock_construct_online_streamer,
    ):

        @tracker.track(capture_output=True)
        def f(x):
            values = ["yielded-1", "yielded-2", "yielded-3"]
            for value in values:
                yield value

        generator = f("generator-input")
        for _ in generator:
            pass

        tracker.flush_tracker()
        mock_construct_online_streamer.assert_called_once()

        EXPECTED_TRACE_TREE = TraceModel(
            id=ANY_BUT_NONE,
            name="f",
            input={"x": "generator-input"},
            output={"output": "['yielded-1', 'yielded-2', 'yielded-3']"},
            start_time=ANY_BUT_NONE,
            end_time=ANY_BUT_NONE,
            spans=[
                SpanModel(
                    id=ANY_BUT_NONE,
                    name="f",
                    input={"x": "generator-input"},
                    output={"output": "['yielded-1', 'yielded-2', 'yielded-3']"},
                    start_time=ANY_BUT_NONE,
                    end_time=ANY_BUT_NONE,
                    spans=[],
                )
            ],
        )

        assert len(fake_message_processor_.trace_trees) == 1

        assert_equal(EXPECTED_TRACE_TREE, fake_message_processor_.trace_trees[0])


def test_track__generator_function_tracked__generator_exhausted_in_another_tracked_function__trace_tree_remains_correct(
    fake_streamer,
):
    fake_message_processor_: (
        backend_emulator_message_processor.BackendEmulatorMessageProcessor
    )
    streamer, fake_message_processor_ = fake_streamer

    mock_construct_online_streamer = mock.Mock()
    mock_construct_online_streamer.return_value = streamer

    with mock.patch.object(
        streamer_constructors,
        "construct_online_streamer",
        mock_construct_online_streamer,
    ):

        @tracker.track(capture_output=True)
        def f_inner(z, generator):
            for _ in generator:
                pass

            return "inner-output"

        @tracker.track(capture_output=True)
        def gen_f(y):
            values = ["yielded-1", "yielded-2", "yielded-3"]
            for value in values:
                yield value

        @tracker.track(capture_output=True)
        def f_outer(x):
            generator = gen_f("generator-input")
            f_inner("inner-input", generator)
            return "outer-output"

        f_outer("outer-input")
        tracker.flush_tracker()
        mock_construct_online_streamer.assert_called_once()

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
                            name="gen_f",
                            input={"y": "generator-input"},
                            output={
                                "output": "['yielded-1', 'yielded-2', 'yielded-3']"
                            },
                            start_time=ANY_BUT_NONE,
                            end_time=ANY_BUT_NONE,
                            spans=[],
                        ),
                        SpanModel(
                            id=ANY_BUT_NONE,
                            name="f_inner",
                            input={"z": "inner-input", "generator": ANY_BUT_NONE},
                            output={"output": "inner-output"},
                            start_time=ANY_BUT_NONE,
                            end_time=ANY_BUT_NONE,
                            spans=[],
                        ),
                    ],
                ),
            ],
        )

        assert len(fake_message_processor_.trace_trees) == 1

        assert_equal(EXPECTED_TRACE_TREE, fake_message_processor_.trace_trees[0])


def test_track__single_async_function_tracked__happyflow(
    fake_streamer,
):
    fake_message_processor_: (
        backend_emulator_message_processor.BackendEmulatorMessageProcessor
    )
    streamer, fake_message_processor_ = fake_streamer

    mock_construct_online_streamer = mock.Mock()
    mock_construct_online_streamer.return_value = streamer

    with mock.patch.object(
        streamer_constructors,
        "construct_online_streamer",
        mock_construct_online_streamer,
    ):

        @tracker.track(capture_output=True)
        async def async_f(x):
            await asyncio.sleep(0.01)
            return "the-output"

        assert asyncio.run(async_f("the-input")) == "the-output"

        tracker.flush_tracker()
        mock_construct_online_streamer.assert_called_once()

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

        assert len(fake_message_processor_.trace_trees) == 1

        assert_equal(EXPECTED_TRACE_TREE, fake_message_processor_.trace_trees[0])


def test_track__nested_async_function_tracked__happyflow(
    fake_streamer,
):
    fake_message_processor_: (
        backend_emulator_message_processor.BackendEmulatorMessageProcessor
    )
    streamer, fake_message_processor_ = fake_streamer

    mock_construct_online_streamer = mock.Mock()
    mock_construct_online_streamer.return_value = streamer

    with mock.patch.object(
        streamer_constructors,
        "construct_online_streamer",
        mock_construct_online_streamer,
    ):

        @tracker.track(capture_output=True)
        async def async_f_inner(y):
            await asyncio.sleep(0.01)
            return "inner-output"

        @tracker.track(capture_output=True)
        async def async_f_outer(x):
            await async_f_inner("inner-input")
            return "outer-output"

        assert asyncio.run(async_f_outer("outer-input")) == "outer-output"

        tracker.flush_tracker()
        mock_construct_online_streamer.assert_called_once()

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

        assert len(fake_message_processor_.trace_trees) == 1

        assert_equal(EXPECTED_TRACE_TREE, fake_message_processor_.trace_trees[0])


def test_track__single_async_generator_function_tracked__generator_exhausted__happyflow(
    fake_streamer,
):
    fake_message_processor_: (
        backend_emulator_message_processor.BackendEmulatorMessageProcessor
    )
    streamer, fake_message_processor_ = fake_streamer

    mock_construct_online_streamer = mock.Mock()
    mock_construct_online_streamer.return_value = streamer

    with mock.patch.object(
        streamer_constructors,
        "construct_online_streamer",
        mock_construct_online_streamer,
    ):

        @tracker.track(capture_output=True)
        async def async_generator(x):
            await asyncio.sleep(0.01)

            for item in ["yielded-1", "yielded-2", "yielded-3"]:
                yield item

        async def async_generator_user():
            async for _ in async_generator("generator-input"):
                pass

        asyncio.run(async_generator_user())

        tracker.flush_tracker()
        mock_construct_online_streamer.assert_called_once()

        EXPECTED_TRACE_TREE = TraceModel(
            id=ANY_BUT_NONE,
            name="async_generator",
            input={"x": "generator-input"},
            output={"output": "['yielded-1', 'yielded-2', 'yielded-3']"},
            start_time=ANY_BUT_NONE,
            end_time=ANY_BUT_NONE,
            spans=[
                SpanModel(
                    id=ANY_BUT_NONE,
                    name="async_generator",
                    input={"x": "generator-input"},
                    output={"output": "['yielded-1', 'yielded-2', 'yielded-3']"},
                    start_time=ANY_BUT_NONE,
                    end_time=ANY_BUT_NONE,
                    spans=[],
                )
            ],
        )

        assert len(fake_message_processor_.trace_trees) == 1

        assert_equal(EXPECTED_TRACE_TREE, fake_message_processor_.trace_trees[0])


def test_track__async_generator_inside_another_tracked_function__happyflow(
    fake_streamer,
):
    fake_message_processor_: (
        backend_emulator_message_processor.BackendEmulatorMessageProcessor
    )
    streamer, fake_message_processor_ = fake_streamer

    mock_construct_online_streamer = mock.Mock()
    mock_construct_online_streamer.return_value = streamer

    with mock.patch.object(
        streamer_constructors,
        "construct_online_streamer",
        mock_construct_online_streamer,
    ):

        @tracker.track(capture_output=True)
        async def async_generator(y):
            await asyncio.sleep(0.01)

            for item in ["yielded-1", "yielded-2", "yielded-3"]:
                yield item

        @tracker.track(capture_output=True)
        async def async_generator_user(x):
            async for _ in async_generator("generator-input"):
                pass

            return "generator-user-output"

        asyncio.run(async_generator_user("generator-user-input"))

        tracker.flush_tracker()
        mock_construct_online_streamer.assert_called_once()

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
                            output={
                                "output": "['yielded-1', 'yielded-2', 'yielded-3']"
                            },
                            start_time=ANY_BUT_NONE,
                            end_time=ANY_BUT_NONE,
                            spans=[],
                        )
                    ],
                )
            ],
        )

        assert len(fake_message_processor_.trace_trees) == 1

        assert_equal(EXPECTED_TRACE_TREE, fake_message_processor_.trace_trees[0])


def test_track__distributed_tracing_with_headers__tracing_is_performed_in_2_threads__all_data_is_saved_in_1_trace_tree(
    fake_streamer,
):
    fake_message_processor_: (
        backend_emulator_message_processor.BackendEmulatorMessageProcessor
    )
    streamer, fake_message_processor_ = fake_streamer

    mock_construct_online_streamer = mock.Mock()
    mock_construct_online_streamer.return_value = streamer

    with mock.patch.object(
        streamer_constructors,
        "construct_online_streamer",
        mock_construct_online_streamer,
    ):

        @tracker.track(capture_output=True)
        def f_remote(y, thread_id):
            return f"f-remote-output-from-{thread_id}"

        def distributed_node_runner(y, thread_id, opik_headers):
            f_remote(y, thread_id, opik_distributed_trace_headers=opik_headers)
            return "result-from-node-runner"

        @tracker.track(capture_output=True)
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
        mock_construct_online_streamer.assert_called_once()

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

        assert len(fake_message_processor_.trace_trees) == 1

        assert_equal(EXPECTED_TRACE_TREE, fake_message_processor_.trace_trees[0])


def test_track__trace_already_created_not_by_decorator__decorator_just_attaches_new_span_to_it__trace_is_not_popped_from_context_in_the_end(
    fake_streamer,
):
    fake_message_processor_: (
        backend_emulator_message_processor.BackendEmulatorMessageProcessor
    )
    streamer, fake_message_processor_ = fake_streamer

    mock_construct_online_streamer = mock.Mock()
    mock_construct_online_streamer.return_value = streamer

    with mock.patch.object(
        streamer_constructors,
        "construct_online_streamer",
        mock_construct_online_streamer,
    ):

        @tracker.track(capture_output=True)
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

        mock_construct_online_streamer.assert_called_once()

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

        assert len(fake_message_processor_.trace_trees) == 1

        assert_equal(EXPECTED_TRACE_TREE, fake_message_processor_.trace_trees[0])


def test_track__span_and_trace_updated_via_opik_context(fake_streamer):
    fake_message_processor_: (
        backend_emulator_message_processor.BackendEmulatorMessageProcessor
    )
    streamer, fake_message_processor_ = fake_streamer

    mock_construct_online_streamer = mock.Mock()
    mock_construct_online_streamer.return_value = streamer

    with mock.patch.object(
        streamer_constructors,
        "construct_online_streamer",
        mock_construct_online_streamer,
    ):

        @tracker.track
        def f(x):
            opik_context.update_current_span(
                name="span-name", metadata={"span-metadata-key": "span-metadata-value"}
            )
            opik_context.update_current_trace(
                name="trace-name",
                metadata={"trace-metadata-key": "trace-metadata-value"},
            )

            return "f-output"

        f("f-input")
        tracker.flush_tracker()
        mock_construct_online_streamer.assert_called_once()

        EXPECTED_TRACE_TREE = TraceModel(
            id=ANY_BUT_NONE,
            name="trace-name",
            input={"x": "f-input"},
            metadata={"trace-metadata-key": "trace-metadata-value"},
            output={"output": "f-output"},
            start_time=ANY_BUT_NONE,
            end_time=ANY_BUT_NONE,
            spans=[
                SpanModel(
                    id=ANY_BUT_NONE,
                    name="span-name",
                    input={"x": "f-input"},
                    metadata={"span-metadata-key": "span-metadata-value"},
                    output={"output": "f-output"},
                    start_time=ANY_BUT_NONE,
                    end_time=ANY_BUT_NONE,
                    spans=[],
                )
            ],
        )

        assert len(fake_message_processor_.trace_trees) == 1

        assert_equal(EXPECTED_TRACE_TREE, fake_message_processor_.trace_trees[0])


def test_track__span_and_trace_updated_via_opik_context_with_feedback_scores__feedback_scores_are_also_logged(
    fake_streamer,
):
    fake_message_processor_: (
        backend_emulator_message_processor.BackendEmulatorMessageProcessor
    )
    streamer, fake_message_processor_ = fake_streamer

    mock_construct_online_streamer = mock.Mock()
    mock_construct_online_streamer.return_value = streamer

    with mock.patch.object(
        streamer_constructors,
        "construct_online_streamer",
        mock_construct_online_streamer,
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
        mock_construct_online_streamer.assert_called_once()

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

        assert len(fake_message_processor_.trace_trees) == 1

        assert_equal(EXPECTED_TRACE_TREE, fake_message_processor_.trace_trees[0])
