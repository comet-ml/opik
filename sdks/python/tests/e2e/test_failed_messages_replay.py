"""
E2E tests for the offline fallback / failed-message replay feature.

Strategy
--------
1. Obtain the internal ReplayManager from the Opik client.
2. Call ``monitor.connection_failed()`` to put the SDK into "offline" mode —
   subsequent messages are persisted as *failed* in the local SQLite store
   instead of being sent to the server.
3. Perform the operations under test (create trace, span, log scores, …).
4. Flush so that any batched messages reach ``OpikMessageProcessor`` while
   the connection is still "down".
5. Call ``monitor.reset()`` to restore ``has_server_connection = True``, then
   ``replay_manager.flush()`` to trigger an immediate replay of failed messages
   back into the Streamer queue, then ``opik_client.flush()`` to drain the queue.
6. Verify that every message reached the server.

Fixtures use ``_use_batching=False`` so that ``CreateTraceMessage`` and
``CreateSpanMessage`` reach ``OpikMessageProcessor`` as individual messages and
are stored in the DB under their own message types.  This avoids having to
reason about the batching preprocessor during replay.
"""

import contextlib
from typing import Generator, List

import pytest
import opik
import opik.api_objects.opik_client
from opik.types import FeedbackScoreDict

from . import verifiers
from ..conftest import random_chars

# ── internal helpers ──────────────────────────────────────────────────────────


def _replay_manager(client: opik.Opik):
    """Return the internal ReplayManager wired into *client*."""
    return client._streamer._fallback_replay_manager


def _simulate_offline(client: opik.Opik) -> None:
    """Put the SDK into offline mode so new messages are queued as *failed*."""
    _replay_manager(client)._monitor.connection_failed("e2e simulated network failure")


def _restore_and_replay(client: opik.Opik) -> None:
    """Restore the connection flag, replay failed messages, drain the queue."""
    mgr = _replay_manager(client)
    mgr._monitor.reset()  # has_server_connection → True
    mgr.flush()  # re-injects failed messages into Streamer
    client.flush()  # waits for the queue to fully drain


@contextlib.contextmanager
def offline_mode(client: opik.Opik) -> Generator[None, None, None]:
    """Context manager: go offline on enter, restore + replay + flush on exit."""
    _simulate_offline(client)
    try:
        yield
    finally:
        _restore_and_replay(client)


# ── fixtures ──────────────────────────────────────────────────────────────────


@pytest.fixture
def non_batching_opik_client(
    configure_e2e_tests_env, shutdown_cached_client_after_test
) -> Generator[opik.Opik, None, None]:
    """Opik client with batching disabled so individual message types are stored
    in the SQLite replay store as-is (no CreateTraceBatchMessage wrapping)."""
    client = opik.api_objects.opik_client.Opik(_use_batching=False)
    yield client
    client.end()


@pytest.fixture
def project_name() -> str:
    return f"e2e-replay-{random_chars()}"


# ── CreateTraceMessage ────────────────────────────────────────────────────────


def test_failed_message_replay__create_trace__replays_successfully(
    non_batching_opik_client: opik.Opik,
    project_name: str,
):
    """CreateTraceMessage stored while offline is delivered after replay."""
    with offline_mode(non_batching_opik_client):
        trace = non_batching_opik_client.trace(
            name="replay-create-trace",
            project_name=project_name,
            input={"key": "offline-input"},
            output={"result": "offline-output"},
            tags=["replay-tag"],
            metadata={"source": "offline"},
        )
        non_batching_opik_client.flush()

    verifiers.verify_trace(
        opik_client=non_batching_opik_client,
        trace_id=trace.id,
        name="replay-create-trace",
        input={"key": "offline-input"},
        output={"result": "offline-output"},
        tags=["replay-tag"],
        metadata={"source": "offline"},
        project_name=project_name,
    )


# ── UpdateTraceMessage ────────────────────────────────────────────────────────


def test_failed_message_replay__update_trace__replays_successfully(
    non_batching_opik_client: opik.Opik,
    project_name: str,
):
    """UpdateTraceMessage stored while offline is delivered after replay."""
    # Create the trace while online so the server record already exists.
    trace = non_batching_opik_client.trace(
        name="replay-update-trace",
        project_name=project_name,
        input={"key": "before"},
    )
    non_batching_opik_client.flush()

    with offline_mode(non_batching_opik_client):
        trace.update(
            output={"updated": True},
            metadata={"source": "offline-update"},
        )
        non_batching_opik_client.flush()

    verifiers.verify_trace(
        opik_client=non_batching_opik_client,
        trace_id=trace.id,
        output={"updated": True},
        metadata={"source": "offline-update"},
        project_name=project_name,
    )


# ── CreateSpanMessage ─────────────────────────────────────────────────────────


def test_failed_message_replay__create_span__replays_successfully(
    non_batching_opik_client: opik.Opik,
    project_name: str,
):
    """CreateSpanMessage stored while offline is delivered after replay."""
    with offline_mode(non_batching_opik_client):
        trace = non_batching_opik_client.trace(
            name="replay-create-span-trace",
            project_name=project_name,
        )
        span = trace.span(
            name="replay-create-span",
            input={"prompt": "offline-prompt"},
            output={"response": "offline-response"},
            type="llm",
            metadata={"source": "offline"},
        )
        non_batching_opik_client.flush()

    verifiers.verify_span(
        opik_client=non_batching_opik_client,
        span_id=span.id,
        trace_id=trace.id,
        parent_span_id=None,
        name="replay-create-span",
        input={"prompt": "offline-prompt"},
        output={"response": "offline-response"},
        type="llm",
        metadata={"source": "offline"},
        project_name=project_name,
    )


# ── UpdateSpanMessage ─────────────────────────────────────────────────────────


def test_failed_message_replay__update_span__replays_successfully(
    non_batching_opik_client: opik.Opik,
    project_name: str,
):
    """UpdateSpanMessage stored while offline is delivered after replay."""
    # Create trace + span online first.
    trace = non_batching_opik_client.trace(
        name="replay-update-span-trace",
        project_name=project_name,
    )
    span = trace.span(
        name="replay-update-span",
        input={"key": "before"},
    )
    non_batching_opik_client.flush()

    with offline_mode(non_batching_opik_client):
        span.update(
            output={"updated": True},
            metadata={"source": "offline-update"},
        )
        non_batching_opik_client.flush()

    verifiers.verify_span(
        opik_client=non_batching_opik_client,
        span_id=span.id,
        trace_id=trace.id,
        parent_span_id=None,
        output={"updated": True},
        metadata={"source": "offline-update"},
        project_name=project_name,
    )


# ── AddTraceFeedbackScoresBatchMessage ────────────────────────────────────────


def test_failed_message_replay__trace_feedback_scores__replays_successfully(
    non_batching_opik_client: opik.Opik,
    project_name: str,
):
    """AddTraceFeedbackScoresBatchMessage stored offline is delivered after replay."""
    trace = non_batching_opik_client.trace(
        name="replay-trace-feedback",
        project_name=project_name,
    )
    non_batching_opik_client.flush()

    with offline_mode(non_batching_opik_client):
        trace.log_feedback_score(
            name="accuracy",
            value=0.9,
            category_name="quality",
            reason="high confidence",
        )
        trace.log_feedback_score(
            name="latency",
            value=0.4,
        )
        non_batching_opik_client.flush()

    expected_scores: List[FeedbackScoreDict] = [
        {
            "id": trace.id,
            "name": "accuracy",
            "value": 0.9,
            "category_name": "quality",
            "reason": "high confidence",
        },
        {
            "id": trace.id,
            "name": "latency",
            "value": 0.4,
            "category_name": None,
            "reason": None,
        },
    ]
    verifiers.verify_trace(
        opik_client=non_batching_opik_client,
        trace_id=trace.id,
        feedback_scores=expected_scores,
    )


# ── AddSpanFeedbackScoresBatchMessage ─────────────────────────────────────────


def test_failed_message_replay__span_feedback_scores__replays_successfully(
    non_batching_opik_client: opik.Opik,
    project_name: str,
):
    """AddSpanFeedbackScoresBatchMessage stored offline is delivered after replay."""
    trace = non_batching_opik_client.trace(
        name="replay-span-feedback-trace",
        project_name=project_name,
    )
    span = trace.span(name="replay-span-feedback")
    non_batching_opik_client.flush()

    with offline_mode(non_batching_opik_client):
        span.log_feedback_score(
            name="relevance",
            value=0.85,
            category_name="relevance",
            reason="on-topic",
        )
        span.log_feedback_score(
            name="toxicity",
            value=0.0,
        )
        non_batching_opik_client.flush()

    expected_scores: List[FeedbackScoreDict] = [
        {
            "id": span.id,
            "name": "relevance",
            "value": 0.85,
            "category_name": "relevance",
            "reason": "on-topic",
        },
        {
            "id": span.id,
            "name": "toxicity",
            "value": 0.0,
            "category_name": None,
            "reason": None,
        },
    ]
    verifiers.verify_span(
        opik_client=non_batching_opik_client,
        span_id=span.id,
        trace_id=trace.id,
        parent_span_id=None,
        feedback_scores=expected_scores,
    )


# ── Comprehensive: all replayable types in one offline window ─────────────────


def test_failed_message_replay__all_replayable_message_types__all_reach_server(
    non_batching_opik_client: opik.Opik,
    project_name: str,
):
    """All supported replayable message types survive a connection failure.

    Covered types
    -------------
    - CreateTraceMessage          (new_trace)
    - UpdateTraceMessage          (trace_for_update.update)
    - CreateSpanMessage           (new_span under new_trace)
    - UpdateSpanMessage           (span_for_update.update)
    - AddTraceFeedbackScoresBatchMessage
    - AddSpanFeedbackScoresBatchMessage
    """
    # ── Phase 1: online — pre-create entities that need server-side records
    #   before updates or feedback scores can be accepted.
    trace_for_update = non_batching_opik_client.trace(
        name="comprehensive-trace-for-update",
        project_name=project_name,
        input={"stage": "online"},
    )
    span_for_update = trace_for_update.span(
        name="comprehensive-span-for-update",
        input={"stage": "online"},
    )
    non_batching_opik_client.flush()

    # ── Phase 2: go offline, perform all operations ────────────────────────────
    with offline_mode(non_batching_opik_client):
        # CreateTraceMessage
        new_trace = non_batching_opik_client.trace(
            name="comprehensive-new-trace",
            project_name=project_name,
            input={"q": "offline-question"},
            output={"a": "offline-answer"},
            tags=["offline"],
            metadata={"batch": "all-types"},
        )

        # CreateSpanMessage (nested under the new trace)
        new_span = new_trace.span(
            name="comprehensive-new-span",
            input={"i": "offline-span-input"},
            output={"o": "offline-span-output"},
            type="general",
        )

        # UpdateTraceMessage
        trace_for_update.update(
            output={"stage": "offline-updated"},
            metadata={"updated_offline": True},
        )

        # UpdateSpanMessage
        span_for_update.update(
            output={"stage": "offline-updated"},
            metadata={"updated_offline": True},
        )

        # AddTraceFeedbackScoresBatchMessage
        new_trace.log_feedback_score("score-new-trace", value=1.0)
        trace_for_update.log_feedback_score("score-updated-trace", value=0.5)

        # AddSpanFeedbackScoresBatchMessage
        new_span.log_feedback_score("score-new-span", value=0.75)
        span_for_update.log_feedback_score("score-updated-span", value=0.25)

        non_batching_opik_client.flush()

    # ── Phase 3: verify every message arrived on the server ───────────────────

    # New trace created offline
    new_trace_scores: List[FeedbackScoreDict] = [
        {
            "id": new_trace.id,
            "name": "score-new-trace",
            "value": 1.0,
            "category_name": None,
            "reason": None,
        }
    ]
    verifiers.verify_trace(
        opik_client=non_batching_opik_client,
        trace_id=new_trace.id,
        name="comprehensive-new-trace",
        input={"q": "offline-question"},
        output={"a": "offline-answer"},
        tags=["offline"],
        metadata={"batch": "all-types"},
        feedback_scores=new_trace_scores,
        project_name=project_name,
    )

    # New span created offline
    new_span_scores: List[FeedbackScoreDict] = [
        {
            "id": new_span.id,
            "name": "score-new-span",
            "value": 0.75,
            "category_name": None,
            "reason": None,
        }
    ]
    verifiers.verify_span(
        opik_client=non_batching_opik_client,
        span_id=new_span.id,
        trace_id=new_trace.id,
        parent_span_id=None,
        name="comprehensive-new-span",
        input={"i": "offline-span-input"},
        output={"o": "offline-span-output"},
        type="general",
        feedback_scores=new_span_scores,
        project_name=project_name,
    )

    # Trace updated offline
    updated_trace_scores: List[FeedbackScoreDict] = [
        {
            "id": trace_for_update.id,
            "name": "score-updated-trace",
            "value": 0.5,
            "category_name": None,
            "reason": None,
        }
    ]
    verifiers.verify_trace(
        opik_client=non_batching_opik_client,
        trace_id=trace_for_update.id,
        output={"stage": "offline-updated"},
        metadata={"updated_offline": True},
        feedback_scores=updated_trace_scores,
        project_name=project_name,
    )

    # Span updated offline
    updated_span_scores: List[FeedbackScoreDict] = [
        {
            "id": span_for_update.id,
            "name": "score-updated-span",
            "value": 0.25,
            "category_name": None,
            "reason": None,
        }
    ]
    verifiers.verify_span(
        opik_client=non_batching_opik_client,
        span_id=span_for_update.id,
        trace_id=trace_for_update.id,
        parent_span_id=None,
        output={"stage": "offline-updated"},
        metadata={"updated_offline": True},
        feedback_scores=updated_span_scores,
        project_name=project_name,
    )


# ── Edge cases ────────────────────────────────────────────────────────────────


def test_failed_message_replay__multiple_offline_windows__all_messages_replayed(
    non_batching_opik_client: opik.Opik,
    project_name: str,
):
    """Messages from several separate offline windows are all replayed correctly."""
    # First offline window
    with offline_mode(non_batching_opik_client):
        t1 = non_batching_opik_client.trace(
            name="replay-window-1", project_name=project_name
        )
        non_batching_opik_client.flush()

    # Second offline window
    with offline_mode(non_batching_opik_client):
        t2 = non_batching_opik_client.trace(
            name="replay-window-2", project_name=project_name
        )
        non_batching_opik_client.flush()

    verifiers.verify_trace(
        opik_client=non_batching_opik_client,
        trace_id=t1.id,
        name="replay-window-1",
        project_name=project_name,
    )
    verifiers.verify_trace(
        opik_client=non_batching_opik_client,
        trace_id=t2.id,
        name="replay-window-2",
        project_name=project_name,
    )


def test_failed_message_replay__no_messages_while_offline__replay_is_noop(
    non_batching_opik_client: opik.Opik,
):
    """Calling replay when there are no failed messages returns 0 and is safe."""
    mgr = _replay_manager(non_batching_opik_client)
    _simulate_offline(non_batching_opik_client)
    non_batching_opik_client.flush()  # nothing queued while offline

    mgr._monitor.reset()
    replayed = mgr.database_manager.replay_failed_messages(
        replay_callback=lambda _: None
    )
    assert replayed == 0, f"Expected 0 replayed messages, got {replayed}"
