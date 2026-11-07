"""Cyclic inputs to @track must not break the tracing context (#9435)."""

from opik import context_storage
from opik.decorator import tracker


def test_track__cyclic_input__span_created_with_cycle_marker_and_later_calls_traced(
    fake_backend,
):
    @tracker.track
    def normal(x):
        return "ok"

    @tracker.track
    def cyclic(x):
        return "ok"

    d = {}
    d["self"] = d

    assert normal({"a": 1}) == "ok"
    assert cyclic(d) == "ok"
    assert normal({"b": 2}) == "ok"
    assert normal({"c": 3}) == "ok"

    tracker.flush_tracker()

    # Every call produced its own trace: the cyclic call did not poison the
    # context, so the later calls are not children of an unsent span.
    assert len(fake_backend.trace_trees) == 4

    cyclic_trace = fake_backend.trace_trees[1]
    assert len(cyclic_trace.spans) == 1
    cyclic_input = cyclic_trace.spans[0].input
    assert cyclic_input == {"x": {"self": "<Cyclic reference to dict>"}}
    assert cyclic_trace.spans[0].output == {"output": "ok"}

    assert fake_backend.trace_trees[2].spans[0].input == {"x": {"b": 2}}
    assert fake_backend.trace_trees[3].spans[0].input == {"x": {"c": 3}}


def test_track__cyclic_input__context_is_empty_after_each_call(fake_backend):
    @tracker.track
    def cyclic(x):
        return "ok"

    d = {}
    d["self"] = d

    cyclic(d)
    assert context_storage.span_data_stack_empty()
    assert context_storage.get_trace_data() is None
