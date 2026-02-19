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

Non-batching tests use ``_use_batching=False`` so that ``CreateTraceMessage``
and ``CreateSpanMessage`` reach ``OpikMessageProcessor`` as individual messages
and are stored in the DB under their own message types.  This avoids having to
reason about the batching preprocessor during replay.

Batching tests use ``_use_batching=True`` (the production default).  In this
mode ``CreateTraceMessage`` / ``CreateSpanMessage`` are accumulated by the
batch preprocessor and flushed as ``CreateTraceBatchMessage`` /
``CreateSpansBatchMessage`` — it is those *batch* messages that are stored in
the SQLite replay store and replayed after the connection is restored.
``UpdateTraceMessage``, ``UpdateSpanMessage``, and the feedback-score batch
messages pass through unchanged in both modes.
"""

import contextlib
from typing import Generator, List

import pytest
import opik
import opik.api_objects.opik_client
from opik.types import FeedbackScoreDict

from opik import Attachment

from . import verifiers
from ..conftest import random_chars
from .conftest import ATTACHMENT_FILE_SIZE

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

    # re-injects failed messages into Streamer
    # waits for the queue to fully drain
    client.flush()


@contextlib.contextmanager
def offline_mode(client: opik.Opik) -> Generator[None, None, None]:
    """Context manager: go offline on entrance, restore + replay + flush on exit."""
    _simulate_offline(client)
    try:
        yield
    finally:
        _restore_and_replay(client)


# ── fixtures ──────────────────────────────────────────────────────────────────


@pytest.fixture
def not_batching_opik_client(
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
    not_batching_opik_client: opik.Opik,
    project_name: str,
):
    """CreateTraceMessage stored while offline is delivered after replay."""
    with offline_mode(not_batching_opik_client):
        trace = not_batching_opik_client.trace(
            name="replay-create-trace",
            project_name=project_name,
            input={"key": "offline-input"},
            output={"result": "offline-output"},
            tags=["replay-tag"],
            metadata={"source": "offline"},
        )
        not_batching_opik_client.flush()

    verifiers.verify_trace(
        opik_client=not_batching_opik_client,
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
    not_batching_opik_client: opik.Opik,
    project_name: str,
):
    """UpdateTraceMessage stored while offline is delivered after replay."""
    # Create the trace while online so the server record already exists.
    trace = not_batching_opik_client.trace(
        name="replay-update-trace",
        project_name=project_name,
        input={"key": "before"},
    )
    not_batching_opik_client.flush()

    with offline_mode(not_batching_opik_client):
        trace.update(
            output={"updated": True},
            metadata={"source": "offline-update"},
        )
        not_batching_opik_client.flush()

    verifiers.verify_trace(
        opik_client=not_batching_opik_client,
        trace_id=trace.id,
        output={"updated": True},
        metadata={"source": "offline-update"},
        project_name=project_name,
    )


# ── CreateSpanMessage ─────────────────────────────────────────────────────────


def test_failed_message_replay__create_span__replays_successfully(
    not_batching_opik_client: opik.Opik,
    project_name: str,
):
    """CreateSpanMessage stored while offline is delivered after replay."""
    with offline_mode(not_batching_opik_client):
        trace = not_batching_opik_client.trace(
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
        not_batching_opik_client.flush()

    verifiers.verify_span(
        opik_client=not_batching_opik_client,
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
    not_batching_opik_client: opik.Opik,
    project_name: str,
):
    """UpdateSpanMessage stored while offline is delivered after replay."""
    # Create trace + span online first.
    trace = not_batching_opik_client.trace(
        name="replay-update-span-trace",
        project_name=project_name,
    )
    span = trace.span(
        name="replay-update-span",
        input={"key": "before"},
    )
    not_batching_opik_client.flush()

    with offline_mode(not_batching_opik_client):
        span.update(
            output={"updated": True},
            metadata={"source": "offline-update"},
        )
        not_batching_opik_client.flush()

    verifiers.verify_span(
        opik_client=not_batching_opik_client,
        span_id=span.id,
        trace_id=trace.id,
        parent_span_id=None,
        output={"updated": True},
        metadata={"source": "offline-update"},
        project_name=project_name,
    )


# ── AddTraceFeedbackScoresBatchMessage ────────────────────────────────────────


def test_failed_message_replay__trace_feedback_scores__replays_successfully(
    not_batching_opik_client: opik.Opik,
    project_name: str,
):
    """AddTraceFeedbackScoresBatchMessage stored offline is delivered after replay."""
    trace = not_batching_opik_client.trace(
        name="replay-trace-feedback",
        project_name=project_name,
    )
    not_batching_opik_client.flush()

    with offline_mode(not_batching_opik_client):
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
        not_batching_opik_client.flush()

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
        opik_client=not_batching_opik_client,
        trace_id=trace.id,
        feedback_scores=expected_scores,
    )


# ── AddSpanFeedbackScoresBatchMessage ─────────────────────────────────────────


def test_failed_message_replay__span_feedback_scores__replays_successfully(
    not_batching_opik_client: opik.Opik,
    project_name: str,
):
    """AddSpanFeedbackScoresBatchMessage stored offline is delivered after replay."""
    trace = not_batching_opik_client.trace(
        name="replay-span-feedback-trace",
        project_name=project_name,
    )
    span = trace.span(name="replay-span-feedback")
    not_batching_opik_client.flush()

    with offline_mode(not_batching_opik_client):
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
        not_batching_opik_client.flush()

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
        opik_client=not_batching_opik_client,
        span_id=span.id,
        trace_id=trace.id,
        parent_span_id=None,
        feedback_scores=expected_scores,
    )


# ── CreateAttachmentMessage ───────────────────────────────────────────────────


def test_failed_message_replay__create_attachment__replays_successfully(
    not_batching_opik_client: opik.Opik,
    project_name: str,
    attachment_data_file,
):
    """CreateAttachmentMessage stored while offline is delivered after replay.

    CreateAttachmentMessage bypasses the batch-manager and is stored in SQLite
    as-is regardless of the batching mode.  The non-batching client is used here
    so that CreateTraceMessage and CreateSpanMessage are stored in SQLite
    *before* their respective CreateAttachmentMessage — guaranteeing that the
    entities exist on the server when the upload is attempted during replay.

    In batching mode the order would be reversed: CreateAttachmentMessage lands
    in SQLite immediately (bypasses the batcher), while the corresponding
    CreateTraceBatchMessage / CreateSpansBatchMessage only arrives after an
    explicit flush — introducing a race between the async upload and the entity
    creation REST call.
    """
    file_name = "replay-attachment.bin"

    with offline_mode(not_batching_opik_client):
        # CreateTraceMessage → SQLite first, then CreateAttachmentMessage → SQLite second.
        trace = not_batching_opik_client.trace(
            name="replay-attachment-trace",
            project_name=project_name,
            attachments=[
                Attachment(
                    data=attachment_data_file.name,
                    file_name=file_name,
                    content_type="application/octet-stream",
                )
            ],
        )
        # CreateSpanMessage → SQLite third, then CreateAttachmentMessage → SQLite fourth.
        span = trace.span(
            name="replay-attachment-span",
            attachments=[
                Attachment(
                    data=attachment_data_file.name,
                    file_name=file_name,
                    content_type="application/octet-stream",
                )
            ],
        )
        not_batching_opik_client.flush()

    expected_attachment = {
        file_name: Attachment(
            data=attachment_data_file.name,
            file_name=file_name,
            content_type="application/octet-stream",
        )
    }

    verifiers.verify_attachments(
        opik_client=not_batching_opik_client,
        entity_type="trace",
        entity_id=trace.id,
        attachments=expected_attachment,
        data_sizes={file_name: ATTACHMENT_FILE_SIZE},
    )
    verifiers.verify_attachments(
        opik_client=not_batching_opik_client,
        entity_type="span",
        entity_id=span.id,
        attachments=expected_attachment,
        data_sizes={file_name: ATTACHMENT_FILE_SIZE},
    )


# ── Comprehensive: all replayable types in one offline window ─────────────────


def test_failed_message_replay__all_replayable_message_types__all_reach_server(
    not_batching_opik_client: opik.Opik,
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
    trace_for_update = not_batching_opik_client.trace(
        name="comprehensive-trace-for-update",
        project_name=project_name,
        input={"stage": "online"},
    )
    span_for_update = trace_for_update.span(
        name="comprehensive-span-for-update",
        input={"stage": "online"},
    )
    not_batching_opik_client.flush()

    # ── Phase 2: go offline, perform all operations ────────────────────────────
    with offline_mode(not_batching_opik_client):
        # CreateTraceMessage
        new_trace = not_batching_opik_client.trace(
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

        not_batching_opik_client.flush()

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
        opik_client=not_batching_opik_client,
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
        opik_client=not_batching_opik_client,
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
        opik_client=not_batching_opik_client,
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
        opik_client=not_batching_opik_client,
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
    not_batching_opik_client: opik.Opik,
    project_name: str,
):
    """Messages from several separate offline windows are all replayed correctly."""
    # First offline window
    with offline_mode(not_batching_opik_client):
        t1 = not_batching_opik_client.trace(
            name="replay-window-1", project_name=project_name
        )
        not_batching_opik_client.flush()

    # Second offline window
    with offline_mode(not_batching_opik_client):
        t2 = not_batching_opik_client.trace(
            name="replay-window-2", project_name=project_name
        )
        not_batching_opik_client.flush()

    verifiers.verify_trace(
        opik_client=not_batching_opik_client,
        trace_id=t1.id,
        name="replay-window-1",
        project_name=project_name,
    )
    verifiers.verify_trace(
        opik_client=not_batching_opik_client,
        trace_id=t2.id,
        name="replay-window-2",
        project_name=project_name,
    )


def test_failed_message_replay__no_messages_while_offline__replay_is_noop(
    not_batching_opik_client: opik.Opik,
):
    """Calling replay when there are no failed messages returns 0 and is safe."""
    mgr = _replay_manager(not_batching_opik_client)
    _simulate_offline(not_batching_opik_client)
    not_batching_opik_client.flush()  # nothing queued while offline

    mgr._monitor.reset()
    replayed = mgr.database_manager.replay_failed_messages(
        replay_callback=lambda _: None
    )
    assert replayed == 0, f"Expected 0 replayed messages, got {replayed}"


# ══════════════════════════════════════════════════════════════════════════════
# BATCHING MODE (_use_batching=True — the production default)
#
# In batching mode CreateTraceMessage / CreateSpanMessage are accumulated by
# the batch preprocessor and flushed as CreateTraceBatchMessage /
# CreateSpansBatchMessage.  Those *batch* messages are what land in SQLite
# when the connection is down, and what get replayed once it is restored.
# ══════════════════════════════════════════════════════════════════════════════

# ── CreateTraceBatchMessage ───────────────────────────────────────────────────


def test_failed_message_replay__batching__create_trace__replays_successfully(
    opik_client: opik.Opik,
    project_name: str,
):
    """CreateTraceBatchMessage stored while offline is delivered after replay."""
    with offline_mode(opik_client):
        trace = opik_client.trace(
            name="replay-batching-create-trace",
            project_name=project_name,
            input={"key": "offline-input"},
            output={"result": "offline-output"},
            tags=["replay-tag"],
            metadata={"source": "offline"},
        )
        opik_client.flush()

    verifiers.verify_trace(
        opik_client=opik_client,
        trace_id=trace.id,
        name="replay-batching-create-trace",
        input={"key": "offline-input"},
        output={"result": "offline-output"},
        tags=["replay-tag"],
        metadata={"source": "offline"},
        project_name=project_name,
    )


# ── UpdateTraceMessage (is not batched, passes through unchanged) ────────────────


def test_failed_message_replay__batching__update_trace__replays_successfully(
    opik_client: opik.Opik,
    project_name: str,
):
    """UpdateTraceMessage stored while offline is delivered after replay (batching mode)."""
    trace = opik_client.trace(
        name="replay-batching-update-trace",
        project_name=project_name,
        input={"key": "before"},
    )
    opik_client.flush()

    with offline_mode(opik_client):
        trace.update(
            output={"updated": True},
            metadata={"source": "offline-update"},
        )
        opik_client.flush()

    verifiers.verify_trace(
        opik_client=opik_client,
        trace_id=trace.id,
        output={"updated": True},
        metadata={"source": "offline-update"},
        project_name=project_name,
    )


# ── CreateSpansBatchMessage ───────────────────────────────────────────────────


def test_failed_message_replay__batching__create_span__replays_successfully(
    opik_client: opik.Opik,
    project_name: str,
):
    """CreateSpansBatchMessage stored while offline is delivered after replay."""
    with offline_mode(opik_client):
        trace = opik_client.trace(
            name="replay-batching-create-span-trace",
            project_name=project_name,
        )
        span = trace.span(
            name="replay-batching-create-span",
            input={"prompt": "offline-prompt"},
            output={"response": "offline-response"},
            type="llm",
            metadata={"source": "offline"},
        )
        opik_client.flush()

    verifiers.verify_span(
        opik_client=opik_client,
        span_id=span.id,
        trace_id=trace.id,
        parent_span_id=None,
        name="replay-batching-create-span",
        input={"prompt": "offline-prompt"},
        output={"response": "offline-response"},
        type="llm",
        metadata={"source": "offline"},
        project_name=project_name,
    )


# ── UpdateSpanMessage (not batched, passes through unchanged) ─────────────────


def test_failed_message_replay__batching__update_span__replays_successfully(
    opik_client: opik.Opik,
    project_name: str,
):
    """UpdateSpanMessage stored while offline is delivered after replay (batching mode)."""
    trace = opik_client.trace(
        name="replay-batching-update-span-trace",
        project_name=project_name,
    )
    span = trace.span(
        name="replay-batching-update-span",
        input={"key": "before"},
    )
    opik_client.flush()

    with offline_mode(opik_client):
        span.update(
            output={"updated": True},
            metadata={"source": "offline-update"},
        )
        opik_client.flush()

    verifiers.verify_span(
        opik_client=opik_client,
        span_id=span.id,
        trace_id=trace.id,
        parent_span_id=None,
        output={"updated": True},
        metadata={"source": "offline-update"},
        project_name=project_name,
    )


# ── AddTraceFeedbackScoresBatchMessage ────────────────────────────────────────


def test_failed_message_replay__batching__trace_feedback_scores__replays_successfully(
    opik_client: opik.Opik,
    project_name: str,
):
    """AddTraceFeedbackScoresBatchMessage stored offline is delivered after replay (batching mode)."""
    trace = opik_client.trace(
        name="replay-batching-trace-feedback",
        project_name=project_name,
    )
    opik_client.flush()

    with offline_mode(opik_client):
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
        opik_client.flush()

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
        opik_client=opik_client,
        trace_id=trace.id,
        feedback_scores=expected_scores,
    )


# ── AddSpanFeedbackScoresBatchMessage ─────────────────────────────────────────


def test_failed_message_replay__batching__span_feedback_scores__replays_successfully(
    opik_client: opik.Opik,
    project_name: str,
):
    """AddSpanFeedbackScoresBatchMessage stored offline is delivered after replay (batching mode)."""
    trace = opik_client.trace(
        name="replay-batching-span-feedback-trace",
        project_name=project_name,
    )
    span = trace.span(name="replay-batching-span-feedback")
    opik_client.flush()

    with offline_mode(opik_client):
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
        opik_client.flush()

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
        opik_client=opik_client,
        span_id=span.id,
        trace_id=trace.id,
        parent_span_id=None,
        feedback_scores=expected_scores,
    )


# ── Comprehensive: all replayable types in one offline window ─────────────────


def test_failed_message_replay__batching__all_replayable_message_types__all_reach_server(
    opik_client: opik.Opik,
    project_name: str,
):
    """All supported replayable message types survive a connection failure (batching mode).

    Covered types
    -------------
    - CreateTraceBatchMessage          (new_trace — via batcher)
    - UpdateTraceMessage               (trace_for_update.update — not batched)
    - CreateSpansBatchMessage          (new_span under new_trace — via batcher)
    - UpdateSpanMessage                (span_for_update.update — not batched)
    - AddTraceFeedbackScoresBatchMessage
    - AddSpanFeedbackScoresBatchMessage
    """
    # ── Phase 1: online — pre-create entities that need server-side records ────
    trace_for_update = opik_client.trace(
        name="batching-comprehensive-trace-for-update",
        project_name=project_name,
        input={"stage": "online"},
    )
    span_for_update = trace_for_update.span(
        name="batching-comprehensive-span-for-update",
        input={"stage": "online"},
    )
    opik_client.flush()

    # ── Phase 2: go offline, perform all operations ────────────────────────────
    with offline_mode(opik_client):
        # CreateTraceBatchMessage (via batcher)
        new_trace = opik_client.trace(
            name="batching-comprehensive-new-trace",
            project_name=project_name,
            input={"q": "offline-question"},
            output={"a": "offline-answer"},
            tags=["offline"],
            metadata={"batch": "all-types"},
        )

        # CreateSpansBatchMessage (via batcher, nested under the new trace)
        new_span = new_trace.span(
            name="batching-comprehensive-new-span",
            input={"i": "offline-span-input"},
            output={"o": "offline-span-output"},
            type="general",
        )

        # UpdateTraceMessage (not batched)
        trace_for_update.update(
            output={"stage": "offline-updated"},
            metadata={"updated_offline": True},
        )

        # UpdateSpanMessage (not batched)
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

        opik_client.flush()

    # ── Phase 3: verify every message arrived on the server ───────────────────

    # New trace created offline (via CreateTraceBatchMessage)
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
        opik_client=opik_client,
        trace_id=new_trace.id,
        name="batching-comprehensive-new-trace",
        input={"q": "offline-question"},
        output={"a": "offline-answer"},
        tags=["offline"],
        metadata={"batch": "all-types"},
        feedback_scores=new_trace_scores,
        project_name=project_name,
    )

    # New span created offline (via CreateSpansBatchMessage)
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
        opik_client=opik_client,
        span_id=new_span.id,
        trace_id=new_trace.id,
        parent_span_id=None,
        name="batching-comprehensive-new-span",
        input={"i": "offline-span-input"},
        output={"o": "offline-span-output"},
        type="general",
        feedback_scores=new_span_scores,
        project_name=project_name,
    )

    # Trace updated offline (via UpdateTraceMessage)
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
        opik_client=opik_client,
        trace_id=trace_for_update.id,
        output={"stage": "offline-updated"},
        metadata={"updated_offline": True},
        feedback_scores=updated_trace_scores,
        project_name=project_name,
    )

    # Span updated offline (via UpdateSpanMessage)
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
        opik_client=opik_client,
        span_id=span_for_update.id,
        trace_id=trace_for_update.id,
        parent_span_id=None,
        output={"stage": "offline-updated"},
        metadata={"updated_offline": True},
        feedback_scores=updated_span_scores,
        project_name=project_name,
    )


# ── Multiple batches in one offline window ────────────────────────────────────


def test_failed_message_replay__batching__multiple_batches__all_messages_delivered(
    opik_client: opik.Opik,
    project_name: str,
):
    """Multiple separate batch records stored in SQLite are all replayed after connection restore.

    Each explicit ``client.flush()`` inside the offline window forces the batcher
    to emit its accumulated messages as a **distinct** batch message that is stored
    as a separate failed record in SQLite.  This exercises the replay path where
    more than one failed record must be fetched and re-injected into the streamer
    queue.

    SQLite records created (in order)
    ----------------------------------
    - CreateTraceBatchMessage  #1  — ``BATCH_SIZE`` traces  (flush 1)
    - CreateTraceBatchMessage  #2  — ``BATCH_SIZE`` traces  (flush 2)
    - CreateSpansBatchMessage  #1  — ``BATCH_SIZE`` spans   (flush 3)

    The same multi-batch behavior is also triggered automatically by the
    time-based flush interval (2 s) and by the max-batch-size limit (1000),
    but the explicit-flush approach lets us verify it without slow sleeps or
    creating thousands of items.
    """
    batch_size = 5

    with offline_mode(opik_client):
        # ── Flush 1: first group of traces → CreateTraceBatchMessage #1 ──────
        first_traces = [
            opik_client.trace(
                name=f"multi-batch-trace-1-{i}",
                project_name=project_name,
                input={"batch": 1, "index": i},
            )
            for i in range(batch_size)
        ]
        opik_client.flush()

        # ── Flush 2: second group of traces → CreateTraceBatchMessage #2 ─────
        second_traces = [
            opik_client.trace(
                name=f"multi-batch-trace-2-{i}",
                project_name=project_name,
                input={"batch": 2, "index": i},
            )
            for i in range(batch_size)
        ]
        opik_client.flush()

        # ── Flush 3: spans under the first trace → CreateSpansBatchMessage #1 ────
        anchor_trace = first_traces[0]
        spans = [
            anchor_trace.span(
                name=f"multi-batch-span-{i}",
                input={"span_index": i},
                output={"result": f"span-result-{i}"},
            )
            for i in range(batch_size)
        ]
        opik_client.flush()

    # Verify all traces from CreateTraceBatchMessage #1
    for i, trace in enumerate(first_traces):
        verifiers.verify_trace(
            opik_client=opik_client,
            trace_id=trace.id,
            name=f"multi-batch-trace-1-{i}",
            input={"batch": 1, "index": i},
            project_name=project_name,
        )

    # Verify all traces from CreateTraceBatchMessage #2
    for i, trace in enumerate(second_traces):
        verifiers.verify_trace(
            opik_client=opik_client,
            trace_id=trace.id,
            name=f"multi-batch-trace-2-{i}",
            input={"batch": 2, "index": i},
            project_name=project_name,
        )

    # Verify all spans from CreateSpansBatchMessage #1
    for i, span in enumerate(spans):
        verifiers.verify_span(
            opik_client=opik_client,
            span_id=span.id,
            trace_id=anchor_trace.id,
            parent_span_id=None,
            name=f"multi-batch-span-{i}",
            input={"span_index": i},
            output={"result": f"span-result-{i}"},
            project_name=project_name,
        )


# ── Edge cases ────────────────────────────────────────────────────────────────


def test_failed_message_replay__batching__multiple_offline_windows__all_messages_replayed(
    opik_client: opik.Opik,
    project_name: str,
):
    """Messages from several separate offline windows are all replayed correctly (batching mode)."""
    # First offline window
    with offline_mode(opik_client):
        t1 = opik_client.trace(
            name="replay-batching-window-1", project_name=project_name
        )
        opik_client.flush()

    # Second offline window
    with offline_mode(opik_client):
        t2 = opik_client.trace(
            name="replay-batching-window-2", project_name=project_name
        )
        opik_client.flush()

    verifiers.verify_trace(
        opik_client=opik_client,
        trace_id=t1.id,
        name="replay-batching-window-1",
        project_name=project_name,
    )
    verifiers.verify_trace(
        opik_client=opik_client,
        trace_id=t2.id,
        name="replay-batching-window-2",
        project_name=project_name,
    )


def test_failed_message_replay__batching__no_messages_while_offline__replay_is_noop(
    opik_client: opik.Opik,
):
    """Calling replay when there are no failed messages returns 0 and is safe (batching mode)."""
    mgr = _replay_manager(opik_client)
    _simulate_offline(opik_client)
    opik_client.flush()  # nothing queued while offline

    mgr._monitor.reset()
    replayed = mgr.database_manager.replay_failed_messages(
        replay_callback=lambda _: None
    )
    assert replayed == 0, f"Expected 0 replayed messages, got {replayed}"
