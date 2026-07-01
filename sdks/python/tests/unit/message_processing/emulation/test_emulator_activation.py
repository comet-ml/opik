"""Ref-counted activation + single recording-context slot on the emulator.

These guard the behavior that lets several concurrent users of a *shared*
processing chain (e.g. evaluate() runs sharing one connection) coordinate
instead of deactivating the emulator out from under each other.
"""

import datetime

from opik.message_processing import messages
from opik.message_processing.emulation import local_emulator_message_processor


def _emulator():
    return local_emulator_message_processor.LocalEmulatorMessageProcessor(active=False)


def _trace_message(trace_id: str = "t1"):
    now = datetime.datetime(2026, 1, 1, tzinfo=datetime.timezone.utc)
    return messages.CreateTraceMessage(
        trace_id=trace_id,
        project_name="p",
        name="trace",
        start_time=now,
        end_time=now,
        input={},
        output={},
        metadata={},
        tags=[],
        error_info=None,
        thread_id=None,
        last_updated_at=now,
        source="sdk",
    )


def test_acquire_release__concurrent_holders__active_until_last_release():
    emulator = _emulator()
    assert not emulator.is_active()

    emulator.acquire()
    emulator.acquire()
    assert emulator.is_active()

    emulator.release()
    assert emulator.is_active()  # one holder remains

    emulator.release()
    assert not emulator.is_active()


def test_release__when_not_acquired__is_noop():
    emulator = _emulator()
    emulator.release()
    assert not emulator.is_active()


def test_release__last_holder_with_reset__clears_recorded_state():
    emulator = _emulator()
    emulator.acquire(reset=True)
    emulator.process(_trace_message())
    assert len(emulator.trace_trees) == 1

    emulator.release(reset=True)

    assert emulator.trace_trees == []


def test_release__reset_deferred_until_last_holder__cleared_only_at_zero():
    emulator = _emulator()
    emulator.acquire(reset=True)
    emulator.process(_trace_message())
    assert len(emulator.trace_trees) == 1

    # A second holder acquires and releases; reset must not fire while a holder
    # remains, so the recorded trace stays intact.
    emulator.acquire(reset=True)
    emulator.release(reset=True)
    assert len(emulator.trace_trees) == 1

    # Only the final release (refcount -> 0) resets the recorded state.
    emulator.release(reset=True)
    assert emulator.trace_trees == []


def test_acquire__first_holder_with_reset__clears_prior_state():
    emulator = _emulator()
    emulator.acquire(reset=True)
    emulator.process(_trace_message())
    emulator.release(reset=False)  # leave the recorded trace in place
    assert len(emulator.trace_trees) == 1

    emulator.acquire(reset=True)  # fresh activation must start clean

    assert emulator.trace_trees == []


def test_begin_recording_context__nested__second_call_refused():
    emulator = _emulator()
    assert emulator.begin_recording_context() is True
    assert emulator.begin_recording_context() is False

    emulator.end_recording_context()
    assert emulator.begin_recording_context() is True


def test_begin_recording_context__while_activated_for_evaluation__still_allowed():
    emulator = _emulator()
    emulator.acquire()  # simulates evaluate() activating the shared emulator

    # A running evaluation must not block record_traces_locally().
    assert emulator.begin_recording_context() is True

    emulator.end_recording_context()
    emulator.release()
