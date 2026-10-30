from typing import Any, Dict
from unittest import mock

import pytest

from opik.api_objects import helpers
from opik.api_objects.experiment import experiment_item
from opik.api_objects.span import span_client
from opik.api_objects.trace import trace_client
from opik.evaluation import rest_operations
from opik.evaluation.metrics import score_result
from opik.message_processing import messages
from opik.validation import feedback_score as feedback_score_validator


def _trace(streamer: mock.MagicMock) -> trace_client.Trace:
    return trace_client.Trace(
        id="trace-id",
        message_streamer=streamer,
        project_name="project",
        url_override="http://localhost",
        source="sdk",
        config=mock.MagicMock(),
    )


def _span(streamer: mock.MagicMock) -> span_client.Span:
    return span_client.Span(
        id="span-id",
        trace_id="trace-id",
        project_name="project",
        message_streamer=streamer,
        url_override="http://localhost",
        source="sdk",
    )


@pytest.mark.parametrize("make_entity", [_trace, _span], ids=["trace", "span"])
def test_log_feedback_score__evaluator_revision__sent_with_score(make_entity):
    streamer = mock.MagicMock()

    make_entity(streamer).log_feedback_score(
        name="accuracy", value=1.0, evaluator_revision="judge@1"
    )

    (batch_message,), _ = streamer.put.call_args
    (score_message,) = batch_message.batch
    assert isinstance(score_message, messages.FeedbackScoreMessage)
    assert score_message.evaluator_revision == "judge@1"


@pytest.mark.parametrize("make_entity", [_trace, _span], ids=["trace", "span"])
def test_log_feedback_score__evaluator_revision_too_long__score_not_sent(
    make_entity,
):
    streamer = mock.MagicMock()

    make_entity(streamer).log_feedback_score(
        name="accuracy", value=1.0, evaluator_revision="r" * 257
    )

    streamer.put.assert_not_called()


@pytest.mark.parametrize(
    "evaluator_revision",
    ["judge@1", "r" * 256, None],
)
def test_feedback_score_validator__evaluator_revision__valid(evaluator_revision):
    score: Dict[str, Any] = {
        "id": "some-id",
        "name": "accuracy",
        "value": 1.0,
        "evaluator_revision": evaluator_revision,
    }

    result = feedback_score_validator.FeedbackScoreValidator(score).validate()

    assert result.ok() is True


@pytest.mark.parametrize(
    "evaluator_revision, expected_error",
    [
        ("r" * 257, "at most 256 characters"),
        (123, "valid string"),
    ],
)
def test_feedback_score_validator__evaluator_revision__invalid(
    evaluator_revision, expected_error
):
    score: Dict[str, Any] = {
        "id": "some-id",
        "name": "accuracy",
        "value": 1.0,
        "evaluator_revision": evaluator_revision,
    }

    result = feedback_score_validator.FeedbackScoreValidator(score).validate()

    assert result.ok() is False
    (reason,) = result.failure_reasons
    assert reason.startswith("feedback_score.evaluator_revision - ")
    assert expected_error in reason


def test_parse_feedback_score_messages__evaluator_revision__carried_to_message():
    (parsed,) = helpers.parse_feedback_score_messages(
        scores=[
            {
                "id": "trace-id",
                "name": "accuracy",
                "value": 1.0,
                "evaluator_revision": "judge@1",
            }
        ],
        project_name="project",
        parsed_item_class=messages.FeedbackScoreMessage,
        logger=mock.MagicMock(),
    )

    assert parsed.evaluator_revision == "judge@1"


def test_log_test_result_feedback_scores__score_result_revision__sent_with_score():
    client = mock.MagicMock()

    rest_operations.log_test_result_feedback_scores(
        client=client,
        score_results=[
            score_result.ScoreResult(
                name="accuracy", value=1.0, evaluator_revision="judge@1"
            ),
            score_result.ScoreResult(name="recall", value=0.5),
        ],
        trace_id="trace-id",
        project_name="project",
    )

    scores = client.log_traces_feedback_scores.call_args.kwargs["scores"]
    revisions = {score["name"]: score["evaluator_revision"] for score in scores}
    assert revisions == {"accuracy": "judge@1", "recall": None}


def test_experiment_item_from_compare_dict__evaluator_revision__read_back():
    item = experiment_item.ExperimentItemContent.from_compare_dict(
        {
            "id": "item-id",
            "trace_id": "trace-id",
            "dataset_item_id": "dataset-item-id",
            "feedback_scores": [
                {"name": "accuracy", "value": 1.0, "evaluator_revision": "judge@1"},
                {"name": "recall", "value": 0.5},
            ],
        }
    )

    scores = {score["name"]: score for score in item.feedback_scores}
    assert scores["accuracy"]["evaluator_revision"] == "judge@1"
    assert "evaluator_revision" not in scores["recall"]
