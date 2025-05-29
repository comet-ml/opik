import threading
import pytest

from opik.api_objects import span, trace
from opik.context_storage import OpikContextStorage


@pytest.fixture
def sample_span(sample_trace):
    return span.SpanData(trace_id=sample_trace.id)


@pytest.fixture
def sample_trace():
    return trace.TraceData()


def test_opik_storage__span_data_stack_empty__top_and_pop_return_None():
    tested = OpikContextStorage()
    assert tested.span_data_stack_empty() is True
    assert tested.top_span_data() is None
    assert tested.pop_span_data() is None


def test_opik_storage__span_data_not_empty_after_adding_span(sample_span):
    tested = OpikContextStorage()
    assert tested.span_data_stack_empty() is True
    tested.add_span_data(sample_span)
    assert tested.span_data_stack_empty() is False


def test_opik_storage__add_span_data__adds_to_stack__the_same_instance_can_be_accessed(
    sample_span,
):
    tested = OpikContextStorage()
    tested.add_span_data(sample_span)
    top_span = tested.top_span_data()
    assert top_span is sample_span


def test_opik_storage__pop_span_data__with_ensure_id_matching_top__returns_span(
    sample_span,
):
    tested = OpikContextStorage()
    tested.add_span_data(sample_span)
    popped_span = tested.pop_span_data(ensure_id=sample_span.id)
    assert popped_span is sample_span


def test_opik_storage__pop_span_data__with_ensure_id_not_matching_top__returns_none(
    sample_span,
):
    tested = OpikContextStorage()
    tested.add_span_data(sample_span)
    popped_span = tested.pop_span_data(ensure_id="non_matching_id")
    assert popped_span is None
    assert tested.top_span_data() is sample_span


def test_opik_storage__pop_span_data__without_ensure_id__returns_span(sample_span):
    tested = OpikContextStorage()
    tested.add_span_data(sample_span)
    popped_span = tested.pop_span_data()
    assert popped_span is sample_span
    assert tested.span_data_stack_empty() is True


def test_opik_storage__pop_span_data__multiple_spans__returns_in_lifo_order():
    tested = OpikContextStorage()
    span1 = span.SpanData(trace_id="some-trace-id")
    span2 = span.SpanData(trace_id="some-trace-id")

    tested.add_span_data(span1)
    tested.add_span_data(span2)

    popped_span = tested.pop_span_data()
    assert popped_span is span2

    popped_span = tested.pop_span_data()
    assert popped_span is span1

    assert tested.span_data_stack_empty() is True


def test_opik_storage__get_trace_data__no_trace_set__returns_none():
    tested = OpikContextStorage()
    assert tested.get_trace_data() is None


def test_opik_storage__set_trace_data__sets_trace__can_be_retrieved(sample_trace):
    tested = OpikContextStorage()
    tested.set_trace_data(sample_trace)
    trace_data = tested.get_trace_data()
    assert trace_data is sample_trace


def test_opik_storage__pop_trace_data__no_trace_set__returns_none():
    tested = OpikContextStorage()
    assert tested.pop_trace_data() is None


def test_opik_storage__pop_trace_data__trace_set__returns_and_removes_trace(
    sample_trace,
):
    tested = OpikContextStorage()
    tested.set_trace_data(sample_trace)
    popped_trace = tested.pop_trace_data()
    assert popped_trace is sample_trace
    assert tested.get_trace_data() is None


def test_opik_storage__pop_trace_data__with_ensure_id_matching__returns_and_removes_trace(
    sample_trace,
):
    tested = OpikContextStorage()
    tested.set_trace_data(sample_trace)
    popped_trace = tested.pop_trace_data(ensure_id=sample_trace.id)
    assert popped_trace is sample_trace
    assert tested.get_trace_data() is None


def test_opik_storage__pop_trace_data__with_ensure_id_not_matching__returns_none(
    sample_trace,
):
    tested = OpikContextStorage()
    tested.set_trace_data(sample_trace)
    popped_trace = tested.pop_trace_data(ensure_id="non_matching_id")
    assert popped_trace is None
    assert tested.get_trace_data() is sample_trace


def test_opik_storage__clear_all__clears_span_stack_and_trace(
    sample_span, sample_trace
):
    tested = OpikContextStorage()
    tested.add_span_data(sample_span)
    tested.set_trace_data(sample_trace)

    tested.clear_all()

    assert tested.span_data_stack_empty() is True
    assert tested.get_trace_data() is None


def test_opik_storage__trim_span_data_stack_to_certain_span__span_not_in_stack__does_nothing(
    sample_span,
):
    tested = OpikContextStorage()
    tested.add_span_data(sample_span)
    tested.trim_span_data_stack_to_certain_span("non_existing_id")
    assert tested.span_data_stack_empty() is False
    assert tested.top_span_data() is sample_span


def test_opik_storage__trim_span_data_stack_to_certain_span__span_in_stack__trims_to_span():
    tested = OpikContextStorage()
    span1 = span.SpanData(trace_id="some-trace-id")
    span2 = span.SpanData(trace_id="some-trace-id")
    span3 = span.SpanData(trace_id="some-trace-id")

    tested.add_span_data(span1)
    tested.add_span_data(span2)
    tested.add_span_data(span3)

    tested.trim_span_data_stack_to_certain_span(span1.id)

    assert tested.top_span_data() is span1
    assert tested.span_data_stack_size() == 1


def test_opik_storage__trim_span_data_stack_to_certain_span__stack_is_empty__does_nothing(
    sample_span,
):
    tested = OpikContextStorage()
    assert tested.span_data_stack_empty() is True
    tested.trim_span_data_stack_to_certain_span("non_existing_id")
    assert tested.span_data_stack_empty() is True


def test_opik_storage__multithreaded__each_thread_has_independent_context():
    """Test that each thread has its own independent context using the same OpikContextStorage instance."""
    shared_context = OpikContextStorage()

    main_trace = trace.TraceData()
    thread1_trace = trace.TraceData()
    thread2_trace = trace.TraceData()

    main_span = span.SpanData(trace_id=main_trace.id)
    thread1_span = span.SpanData(trace_id=thread1_trace.id)
    thread2_span = span.SpanData(trace_id=thread2_trace.id)

    def thread1_func():
        assert shared_context.get_trace_data() is None
        shared_context.set_trace_data(thread1_trace)
        assert shared_context.get_trace_data() is thread1_trace

        assert shared_context.span_data_stack_empty() is True
        shared_context.add_span_data(thread1_span)
        assert shared_context.top_span_data() is thread1_span

    def thread2_func():
        assert shared_context.get_trace_data() is None
        shared_context.set_trace_data(thread2_trace)
        assert shared_context.get_trace_data() is thread2_trace

        assert shared_context.span_data_stack_empty() is True
        shared_context.add_span_data(thread2_span)
        assert shared_context.top_span_data() is thread2_span

    shared_context.set_trace_data(main_trace)
    shared_context.add_span_data(main_span)

    thread1 = threading.Thread(target=thread1_func)
    thread2 = threading.Thread(target=thread2_func)
    thread1.start()
    thread2.start()
    thread1.join()
    thread2.join()

    # Main thread context is not affected by the changes in children threads
    assert shared_context.span_data_stack_empty() is False
    assert shared_context.get_trace_data() is main_trace
