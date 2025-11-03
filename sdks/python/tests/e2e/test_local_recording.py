import opik
import pytest
from opik.message_processing import message_processors_chain
from opik.message_processing.emulation.models import TraceModel, SpanModel
from opik.api_objects import opik_client

from ..testlib import assert_equal, ANY_BUT_NONE


@opik.track
def _sample_tracked_function(x: str) -> str:
    return f"out:{x}"


def test_records_spans_and_traces__happy_path():
    with opik.record_traces_locally() as storage:
        _sample_tracked_function("a")

        span_trees = storage.span_trees
        trace_trees = storage.trace_trees

    assert isinstance(span_trees, list)
    assert isinstance(trace_trees, list)
    assert len(span_trees) == 1
    assert len(trace_trees) == 1

    EXPECTED_TRACE_TREE = TraceModel(
        id=ANY_BUT_NONE,
        start_time=ANY_BUT_NONE,
        name="_sample_tracked_function",
        project_name="Default Project",
        input={"x": "a"},
        output={"output": "out:a"},
        end_time=ANY_BUT_NONE,
        spans=[
            SpanModel(
                id=ANY_BUT_NONE,
                start_time=ANY_BUT_NONE,
                name="_sample_tracked_function",
                input={"x": "a"},
                output={"output": "out:a"},
                type="general",
                end_time=ANY_BUT_NONE,
                project_name="Default Project",
                last_updated_at=ANY_BUT_NONE,
            )
        ],
        last_updated_at=ANY_BUT_NONE,
    )

    assert_equal(expected=EXPECTED_TRACE_TREE, actual=trace_trees[0])


def test_prevents_nested_usage():
    with opik.record_traces_locally():
        with pytest.raises(RuntimeError):
            with opik.record_traces_locally():
                pass


def test_cleanup_and_reuse_after_exit__should_save_new_data():
    client = opik_client.get_client_cached()

    # First run: record and ensure the local processor becomes active
    with opik.record_traces_locally() as storage:
        _ = _sample_tracked_function("first run")
        assert len(storage.span_trees) == 1
        assert len(storage.trace_trees) == 1

        trace_trees = storage.trace_trees

        local = message_processors_chain.get_local_emulator_message_processor(
            chain=client._message_processor
        )
        assert local is not None and local.is_active()

    EXPECTED_TRACE_TREE = TraceModel(
        id=ANY_BUT_NONE,
        start_time=ANY_BUT_NONE,
        name="_sample_tracked_function",
        project_name="Default Project",
        input={"x": "first run"},
        output={"output": "out:first run"},
        end_time=ANY_BUT_NONE,
        spans=[
            SpanModel(
                id=ANY_BUT_NONE,
                start_time=ANY_BUT_NONE,
                name="_sample_tracked_function",
                input={"x": "first run"},
                output={"output": "out:first run"},
                type="general",
                end_time=ANY_BUT_NONE,
                project_name="Default Project",
                last_updated_at=ANY_BUT_NONE,
            )
        ],
        last_updated_at=ANY_BUT_NONE,
    )

    assert_equal(expected=EXPECTED_TRACE_TREE, actual=trace_trees[0])

    # After context exit: local processor should be inactive and reset on the next activation
    local = message_processors_chain.get_local_emulator_message_processor(
        chain=client._message_processor
    )
    assert local is not None and not local.is_active()

    # The second run should work independently
    with opik.record_traces_locally() as storage:
        _ = _sample_tracked_function("second run")

        assert len(storage.span_trees) == 1
        assert len(storage.trace_trees) == 1

        trace_trees = storage.trace_trees

    EXPECTED_TRACE_TREE = TraceModel(
        id=ANY_BUT_NONE,
        start_time=ANY_BUT_NONE,
        name="_sample_tracked_function",
        project_name="Default Project",
        input={"x": "second run"},
        output={"output": "out:second run"},
        end_time=ANY_BUT_NONE,
        spans=[
            SpanModel(
                id=ANY_BUT_NONE,
                start_time=ANY_BUT_NONE,
                name="_sample_tracked_function",
                input={"x": "second run"},
                output={"output": "out:second run"},
                type="general",
                end_time=ANY_BUT_NONE,
                project_name="Default Project",
                last_updated_at=ANY_BUT_NONE,
            )
        ],
        last_updated_at=ANY_BUT_NONE,
    )

    assert_equal(expected=EXPECTED_TRACE_TREE, actual=trace_trees[0])


def test_handle_flushes_before_access(monkeypatch):
    flushed = {"called": False}

    original_flush = opik.Opik.flush

    def _flush(self, timeout=None):  # type: ignore[no-redef]
        flushed["called"] = True
        return original_flush(self, timeout)

    monkeypatch.setattr(opik.Opik, "flush", _flush, raising=True)

    with opik.record_traces_locally() as storage:
        _ = _sample_tracked_function("c")

        _ = storage.span_trees
        assert flushed["called"] is True
