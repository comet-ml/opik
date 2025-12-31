from typing import List

import pytest
from unittest.mock import patch, MagicMock

from opik.api_objects import opik_client
from opik.message_processing import messages
from opik.types import BatchFeedbackScoreDict


@pytest.mark.parametrize(
    "trace_id,project_name",
    [
        (None, "some-project"),
        ("some-trace-id", None),
        (None, None),
        ("", "some-project"),
        ("some-trace-id", ""),
        ("", ""),
    ],
)
def test_opik_client__update_trace__missing_mandatory_parameters__error_raised(
    trace_id, project_name
):
    opik_client_ = opik_client.Opik()

    with pytest.raises(ValueError):
        opik_client_.update_trace(trace_id=trace_id, project_name=project_name)


def test_opik_client__update_experiment__both_name_and_config__both_sent_to_api():
    opik_client_ = opik_client.Opik()

    with patch.object(
        opik_client_._rest_client.experiments, "update_experiment"
    ) as mock_update:
        new_config = {"model": "gpt-4", "temperature": 0.7}
        opik_client_.update_experiment(
            id="some-experiment-id", name="new-name", experiment_config=new_config
        )

        mock_update.assert_called_once()
        call_kwargs = mock_update.call_args[1]
        assert call_kwargs["name"] == "new-name"
        assert call_kwargs["metadata"] == new_config


def test_opik_client__update_experiment__name_only__only_name_sent_to_api():
    opik_client_ = opik_client.Opik()

    with patch.object(
        opik_client_._rest_client.experiments, "update_experiment"
    ) as mock_update:
        opik_client_.update_experiment(id="some-experiment-id", name="new-name")

        mock_update.assert_called_once()
        call_kwargs = mock_update.call_args[1]
        assert call_kwargs["name"] == "new-name"
        assert "metadata" not in call_kwargs


def test_opik_client__update_experiment__config_only__only_metadata_sent_to_api():
    opik_client_ = opik_client.Opik()

    with patch.object(
        opik_client_._rest_client.experiments, "update_experiment"
    ) as mock_update:
        new_config = {"model": "gpt-4", "temperature": 0.7}
        opik_client_.update_experiment(
            id="some-experiment-id", experiment_config=new_config
        )

        mock_update.assert_called_once()
        call_kwargs = mock_update.call_args[1]
        assert "name" not in call_kwargs
        assert call_kwargs["metadata"] == new_config


@pytest.mark.parametrize(
    "experiment_id",
    [
        None,
        "",
    ],
)
def test_opik_client__update_experiment__missing_mandatory_parameters__error_raised(
    experiment_id,
):
    opik_client_ = opik_client.Opik()

    with pytest.raises(ValueError):
        opik_client_.update_experiment(id=experiment_id)


def test_opik_client__update_experiment__no_update_parameters__error_raised():
    opik_client_ = opik_client.Opik()

    with pytest.raises(ValueError):
        opik_client_.update_experiment(id="some-experiment-id")


def test_opik_client__log_spans_feedback_scores__happy_path():
    """Test log_spans_feedback_scores with valid scores."""
    opik_client_ = opik_client.Opik(project_name="test-project")
    mock_streamer = MagicMock()
    opik_client_._streamer = mock_streamer

    scores: List[BatchFeedbackScoreDict] = [
        {
            "id": "span-1",
            "name": "quality",
            "value": 0.85,
            "reason": "Good quality",
        },
        {
            "id": "span-2",
            "name": "latency",
            "value": 0.75,
            "category_name": "performance",
        },
    ]

    # Mock parse_feedback_score_messages to return valid messages
    mock_score_messages = [
        messages.FeedbackScoreMessage(
            id="span-1",
            project_name="test-project",
            name="quality",
            value=0.85,
            source="sdk",
            reason="Good quality",
        ),
        messages.FeedbackScoreMessage(
            id="span-2",
            project_name="test-project",
            name="latency",
            value=0.75,
            source="sdk",
            category_name="performance",
        ),
    ]

    with (
        patch("opik.api_objects.helpers.parse_feedback_score_messages") as mock_parse,
        patch(
            "opik.message_processing.batching.sequence_splitter.split_into_batches"
        ) as mock_batch,
    ):
        mock_parse.return_value = mock_score_messages
        mock_batch.return_value = [mock_score_messages]  # Single batch

        opik_client_.log_spans_feedback_scores(scores=scores)

        # Verify parse_feedback_score_messages was called correctly
        mock_parse.assert_called_once()
        call_kwargs = mock_parse.call_args[1]
        assert call_kwargs["scores"] == scores
        assert call_kwargs["project_name"] == "test-project"
        assert call_kwargs["parsed_item_class"] == messages.FeedbackScoreMessage

        # Verify batching was called
        mock_batch.assert_called_once()
        batch_call_args = mock_batch.call_args[0]
        assert batch_call_args[0] == mock_score_messages

        # Verify streamer.put was called with AddSpanFeedbackScoresBatchMessage
        mock_streamer.put.assert_called_once()
        put_call_arg = mock_streamer.put.call_args[0][0]
        assert isinstance(put_call_arg, messages.AddSpanFeedbackScoresBatchMessage)
        assert put_call_arg.batch == mock_score_messages


def test_opik_client__log_spans_feedback_scores__with_explicit_project_name():
    """Test log_spans_feedback_scores with explicit project_name parameter."""
    opik_client_ = opik_client.Opik(project_name="default-project")
    mock_streamer = MagicMock()
    opik_client_._streamer = mock_streamer

    scores: List[BatchFeedbackScoreDict] = [
        {"id": "span-1", "name": "quality", "value": 0.85}
    ]

    mock_score_messages = [
        messages.FeedbackScoreMessage(
            id="span-1",
            project_name="explicit-project",
            name="quality",
            value=0.85,
            source="sdk",
        )
    ]

    with (
        patch("opik.api_objects.helpers.parse_feedback_score_messages") as mock_parse,
        patch(
            "opik.message_processing.batching.sequence_splitter.split_into_batches"
        ) as mock_batch,
    ):
        mock_parse.return_value = mock_score_messages
        mock_batch.return_value = [mock_score_messages]

        opik_client_.log_spans_feedback_scores(
            scores=scores, project_name="explicit-project"
        )

        # Verify project_name parameter was used
        call_kwargs = mock_parse.call_args[1]
        assert call_kwargs["project_name"] == "explicit-project"


def test_opik_client__log_spans_feedback_scores__no_valid_scores():
    """Test log_spans_feedback_scores when no valid scores are provided."""
    opik_client_ = opik_client.Opik(project_name="test-project")
    mock_streamer = MagicMock()
    opik_client_._streamer = mock_streamer

    scores: List[BatchFeedbackScoreDict] = [
        {"id": "span-1", "name": "quality"}  # Missing required 'value' field
    ]

    with (
        patch("opik.api_objects.helpers.parse_feedback_score_messages") as mock_parse,
        patch("opik.api_objects.opik_client.LOGGER") as mock_logger,
    ):
        mock_parse.return_value = None  # No valid scores

        opik_client_.log_spans_feedback_scores(scores=scores)

        # Verify error was logged
        mock_logger.error.assert_called_once()
        error_message = mock_logger.error.call_args[0][0]
        assert "No valid spans feedback scores" in error_message
        assert str(scores) in error_message

        # Verify streamer.put was NOT called
        mock_streamer.put.assert_not_called()


def test_opik_client__log_traces_feedback_scores__happy_path():
    """Test log_traces_feedback_scores with valid scores."""
    opik_client_ = opik_client.Opik(project_name="test-project")
    mock_streamer = MagicMock()
    opik_client_._streamer = mock_streamer

    scores: List[BatchFeedbackScoreDict] = [
        {
            "id": "trace-1",
            "name": "accuracy",
            "value": 0.92,
            "reason": "High accuracy",
        }
    ]

    mock_score_messages = [
        messages.FeedbackScoreMessage(
            id="trace-1",
            project_name="test-project",
            name="accuracy",
            value=0.92,
            source="sdk",
            reason="High accuracy",
        )
    ]

    with (
        patch("opik.api_objects.helpers.parse_feedback_score_messages") as mock_parse,
        patch(
            "opik.message_processing.batching.sequence_splitter.split_into_batches"
        ) as mock_batch,
    ):
        mock_parse.return_value = mock_score_messages
        mock_batch.return_value = [mock_score_messages]

        opik_client_.log_traces_feedback_scores(scores=scores)

        # Verify parse_feedback_score_messages was called correctly
        mock_parse.assert_called_once()
        call_kwargs = mock_parse.call_args[1]
        assert call_kwargs["scores"] == scores
        assert call_kwargs["project_name"] == "test-project"
        assert call_kwargs["parsed_item_class"] == messages.FeedbackScoreMessage

        # Verify streamer.put was called with AddTraceFeedbackScoresBatchMessage
        mock_streamer.put.assert_called_once()
        put_call_arg = mock_streamer.put.call_args[0][0]
        assert isinstance(put_call_arg, messages.AddTraceFeedbackScoresBatchMessage)
        assert put_call_arg.batch == mock_score_messages


def test_opik_client__log_traces_feedback_scores__with_explicit_project_name():
    """Test log_traces_feedback_scores with explicit project_name parameter."""
    opik_client_ = opik_client.Opik(project_name="default-project")
    mock_streamer = MagicMock()
    opik_client_._streamer = mock_streamer

    scores: List[BatchFeedbackScoreDict] = [
        {"id": "trace-1", "name": "accuracy", "value": 0.92}
    ]

    mock_score_messages = [
        messages.FeedbackScoreMessage(
            id="trace-1",
            project_name="explicit-project",
            name="accuracy",
            value=0.92,
            source="sdk",
        )
    ]

    with (
        patch("opik.api_objects.helpers.parse_feedback_score_messages") as mock_parse,
        patch(
            "opik.message_processing.batching.sequence_splitter.split_into_batches"
        ) as mock_batch,
    ):
        mock_parse.return_value = mock_score_messages
        mock_batch.return_value = [mock_score_messages]

        opik_client_.log_traces_feedback_scores(
            scores=scores, project_name="explicit-project"
        )

        # Verify project_name parameter was used
        call_kwargs = mock_parse.call_args[1]
        assert call_kwargs["project_name"] == "explicit-project"


def test_opik_client__log_traces_feedback_scores__no_valid_scores():
    """Test log_traces_feedback_scores when no valid scores are provided."""
    opik_client_ = opik_client.Opik(project_name="test-project")
    mock_streamer = MagicMock()
    opik_client_._streamer = mock_streamer

    scores: List[BatchFeedbackScoreDict] = [
        {"id": "trace-1", "name": "accuracy"}  # Missing required 'value' field
    ]

    with (
        patch("opik.api_objects.helpers.parse_feedback_score_messages") as mock_parse,
        patch("opik.api_objects.opik_client.LOGGER") as mock_logger,
    ):
        mock_parse.return_value = None  # No valid scores

        opik_client_.log_traces_feedback_scores(scores=scores)

        # Verify error was logged
        mock_logger.error.assert_called_once()
        error_message = mock_logger.error.call_args[0][0]
        assert "No valid traces feedback scores" in error_message
        assert str(scores) in error_message

        # Verify streamer.put was NOT called
        mock_streamer.put.assert_not_called()
