"""Pins ``evaluator_revision`` in the feedback-score request bodies.

Each feedback-score batch is sent through OpikMessageProcessor into the
generated client over an ``httpx.MockTransport``, and the assertion is on the
decoded request body, so a model or serializer change that drops the field
fails here instead of losing the revision without an error.
"""

import json
from typing import Generator, List, Optional, Type
from unittest import mock

import httpx
import pytest

from opik.message_processing import data_loss, messages, permissions
from opik.message_processing.processors import online_message_processor
from opik.message_processing.replay import replay_manager
from opik.rest_api import client as rest_api_client


class _RecordingTransport:
    def __init__(self) -> None:
        self.requests: List[httpx.Request] = []

    def handle(self, request: httpx.Request) -> httpx.Response:
        self.requests.append(request)
        return httpx.Response(204)


@pytest.fixture
def transport() -> _RecordingTransport:
    return _RecordingTransport()


@pytest.fixture
def processor(
    transport: _RecordingTransport,
) -> Generator[online_message_processor.OpikMessageProcessor, None, None]:
    with httpx.Client(transport=httpx.MockTransport(transport.handle)) as httpx_client:
        rest_client = rest_api_client.OpikApi(
            base_url="http://localhost:5173/api",
            api_key="api-key",
            workspace_name="workspace",
            httpx_client=httpx_client,
        )
        registry = mock.MagicMock(spec=permissions.UnauthorizedMessageTypeRegistry)
        registry.is_authorized.return_value = True
        yield online_message_processor.OpikMessageProcessor(
            rest_client=rest_client,
            file_upload_manager=mock.MagicMock(),
            fallback_replay_manager=mock.MagicMock(spec=replay_manager.ReplayManager),
            unauthorized_message_types_registry=registry,
            data_loss_tracker=data_loss.DataLossTracker(),
        )


@pytest.mark.parametrize(
    "evaluator_revision", ["judge@3f9c2a1", None], ids=["with-revision", "without"]
)
@pytest.mark.parametrize(
    "batch_message_type, score_message_type, path, id_key",
    [
        (
            messages.AddTraceFeedbackScoresBatchMessage,
            messages.FeedbackScoreMessage,
            "/api/v1/private/traces/feedback-scores",
            "id",
        ),
        (
            messages.AddSpanFeedbackScoresBatchMessage,
            messages.FeedbackScoreMessage,
            "/api/v1/private/spans/feedback-scores",
            "id",
        ),
        (
            messages.AddThreadsFeedbackScoresBatchMessage,
            messages.ThreadsFeedbackScoreMessage,
            "/api/v1/private/traces/threads/feedback-scores",
            "thread_id",
        ),
    ],
    ids=["traces", "spans", "threads"],
)
def test_process__feedback_scores_batch__evaluator_revision_reaches_request_body(
    processor: online_message_processor.OpikMessageProcessor,
    transport: _RecordingTransport,
    batch_message_type: Type[messages.BaseMessage],
    score_message_type: Type[messages.FeedbackScoreMessage],
    path: str,
    id_key: str,
    evaluator_revision: Optional[str],
) -> None:
    score_message = score_message_type(
        id="entity-id",
        project_name="project",
        name="hallucination",
        value=0.25,
        source="sdk",
        evaluator_revision=evaluator_revision,
    )

    processor.process(batch_message_type(batch=[score_message]))

    assert len(transport.requests) == 1
    request = transport.requests[0]
    assert request.url.path == path
    (score,) = json.loads(request.content)["scores"]
    assert score[id_key] == "entity-id"
    # An unset revision goes out as an explicit null, like reason and category_name.
    assert "evaluator_revision" in score
    assert score["evaluator_revision"] == evaluator_revision
