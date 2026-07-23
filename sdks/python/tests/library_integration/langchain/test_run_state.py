from typing import List, Optional
from uuid import UUID, uuid4

from opik.api_objects import span as span_module, trace as trace_module
from opik.integrations.langchain.run_state import RunStateStore


class _FakeRun:
    """Minimal stand-in for a LangChain ``Run`` for release_run_tree tests.

    RunStateStore only reads ``id`` and ``child_runs`` off a run, so a tree of
    these is enough to exercise the subtree walk without a real LangChain run.
    """

    def __init__(self, run_id: UUID, child_runs: Optional[List["_FakeRun"]] = None):
        self.id = run_id
        self.child_runs = child_runs or []


def test_run_state__save_and_get_span_and_trace_data():
    store = RunStateStore()
    run_id = uuid4()
    span_data = span_module.SpanData(trace_id="trace-1", name="span")
    trace_data = trace_module.TraceData(name="trace")

    store.save_span_data(run_id, span_data)
    store.save_trace_data(run_id, trace_data)

    assert store.get_span_data(run_id) is span_data
    assert store.get_trace_data(run_id) is trace_data
    assert store.get_span_data(uuid4()) is None
    assert store.get_trace_data(uuid4()) is None


def test_run_state__spans_for_trace_returns_only_matching_spans():
    store = RunStateStore()
    store.save_span_data(uuid4(), span_module.SpanData(trace_id="trace-1", name="a"))
    store.save_span_data(uuid4(), span_module.SpanData(trace_id="trace-1", name="b"))
    store.save_span_data(uuid4(), span_module.SpanData(trace_id="trace-2", name="c"))

    names = sorted(span_data.name for span_data in store.spans_for_trace("trace-1"))

    assert names == ["a", "b"]
    assert list(store.spans_for_trace("unknown-trace")) == []


def test_run_state__skipped_langgraph_root_mark_is_queryable():
    store = RunStateStore()
    run_id = uuid4()

    assert store.is_skipped_langgraph_root(run_id) is False
    store.mark_skipped_langgraph_root(run_id)
    assert store.is_skipped_langgraph_root(run_id) is True


def test_run_state__owns_trace_is_true_only_for_stored_trace_data():
    """The tracer owns (and must finalize) exactly the traces it created; a trace
    it never stored - an ambient/distributed/caller trace - is not owned."""
    store = RunStateStore()
    own_trace_data = trace_module.TraceData(name="trace")
    store.save_trace_data(uuid4(), own_trace_data)

    assert store.owns_trace(own_trace_data.id) is True
    assert store.owns_trace("some-external-trace-id") is False


def test_run_state__link_child_run_uses_parent_trace_data_when_present():
    store = RunStateStore()
    parent_run_id, child_run_id = uuid4(), uuid4()
    trace_data = trace_module.TraceData(name="trace")
    store.save_trace_data(parent_run_id, trace_data)

    store.link_child_run_to_parent_trace(
        child_run_id=child_run_id,
        parent_run_id=parent_run_id,
        trace_id=trace_data.id,
    )

    assert store.get_trace_data(child_run_id) is trace_data


def test_run_state__link_child_run_falls_back_to_trace_id_lookup():
    """A stream-restart root exists only as a span (no trace data of its own),
    so the child must inherit the trace data found by trace_id."""
    store = RunStateStore()
    original_root_run_id, stream_restart_run_id, child_run_id = (
        uuid4(),
        uuid4(),
        uuid4(),
    )
    trace_data = trace_module.TraceData(name="trace")
    store.save_trace_data(original_root_run_id, trace_data)
    # The stream-restart root is only known as a span with the same trace_id.
    store.save_span_data(
        stream_restart_run_id,
        span_module.SpanData(trace_id=trace_data.id, name="stream-restart-span"),
    )

    store.link_child_run_to_parent_trace(
        child_run_id=child_run_id,
        parent_run_id=stream_restart_run_id,
        trace_id=trace_data.id,
    )

    assert store.get_trace_data(child_run_id) is trace_data


def test_run_state__link_child_run_leaves_child_unlinked_when_nothing_matches():
    store = RunStateStore()
    child_run_id = uuid4()

    store.link_child_run_to_parent_trace(
        child_run_id=child_run_id,
        parent_run_id=uuid4(),
        trace_id="unknown-trace",
    )

    assert store.get_trace_data(child_run_id) is None


def test_run_state__release_run_tree_empties_all_state_for_the_subtree():
    store = RunStateStore()
    root_run_id, child_run_id, grandchild_run_id = uuid4(), uuid4(), uuid4()
    trace_data = trace_module.TraceData(name="trace")

    store.save_trace_data(root_run_id, trace_data)
    store.mark_skipped_langgraph_root(root_run_id)
    store.save_span_data(
        child_run_id, span_module.SpanData(trace_id=trace_data.id, name="child")
    )
    store.save_span_data(
        grandchild_run_id,
        span_module.SpanData(trace_id=trace_data.id, name="grandchild"),
    )

    root_run = _FakeRun(
        root_run_id,
        child_runs=[_FakeRun(child_run_id, child_runs=[_FakeRun(grandchild_run_id)])],
    )
    store.release_run_tree(root_run)

    assert store.is_empty()
    assert store.get_trace_data(root_run_id) is None
    assert store.get_span_data(child_run_id) is None
    assert store.get_span_data(grandchild_run_id) is None
    assert store.is_skipped_langgraph_root(root_run_id) is False


def test_run_state__release_run_tree_keeps_unrelated_runs():
    store = RunStateStore()
    finished_run_id, other_run_id = uuid4(), uuid4()
    store.save_span_data(
        finished_run_id, span_module.SpanData(trace_id="trace-1", name="finished")
    )
    store.save_span_data(
        other_run_id, span_module.SpanData(trace_id="trace-2", name="other")
    )

    store.release_run_tree(_FakeRun(finished_run_id))

    assert store.get_span_data(finished_run_id) is None
    assert store.get_span_data(other_run_id) is not None
    assert store.is_empty() is False
