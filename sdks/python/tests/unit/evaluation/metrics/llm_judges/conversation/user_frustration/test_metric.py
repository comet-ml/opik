import json
from unittest import mock

import pytest

from opik import exceptions
from opik.evaluation.metrics.conversation.llm_judges.user_frustration import schema
from opik.evaluation.metrics.conversation.llm_judges.user_frustration.metric import (
    UserFrustrationMetric,
)
from opik.evaluation.models import base_model


@pytest.fixture
def simple_conversation():
    return [
        {"role": "user", "content": "Hello!"},
        {"role": "assistant", "content": "Hi there! How can I help you?"},
        {"role": "user", "content": "What's the weather like?"},
        {
            "role": "assistant",
            "content": "I don't have real-time weather data, but I can help you find it.",
        },
        {"role": "user", "content": "That's helpful, thanks!"},
    ]


@pytest.fixture
def frustrated_conversation():
    return [
        {"role": "user", "content": "Hello!"},
        {"role": "assistant", "content": "Hi there! How can I help you?"},
        {"role": "user", "content": "How do I center a div using CSS?"},
        {
            "role": "assistant",
            "content": "There are many ways to center elements in CSS.",
        },
        {"role": "user", "content": "Okay... can you show me one?"},
        {
            "role": "assistant",
            "content": "Sure. It depends on the context — are you centering horizontally, vertically, or both?",
        },
        {"role": "user", "content": "Both. Just give me a basic example."},
    ]


@pytest.fixture
def mock_model():
    model = mock.MagicMock(spec=base_model.OpikBaseModel)
    return model


def _no_frustration_side_effect(*args, **kwargs):
    response_format = kwargs.get("response_format")
    if response_format == schema.EvaluateUserFrustrationResponse:
        return json.dumps({"verdict": "no"})
    elif response_format == schema.ScoreReasonResponse:
        return json.dumps(
            {"reason": "The conversation shows no signs of user frustration."}
        )
    return "{}"


def test_score__with_no_frustration(mock_model, simple_conversation):
    """Test scoring with no user frustration."""

    # Mock model response to return no as verdicts (no frustration)
    mock_model.generate_string.side_effect = _no_frustration_side_effect

    metric = UserFrustrationMetric(
        model=mock_model,
        name="test_frustration",
        include_reason=True,
        window_size=2,
        track=False,
    )
    # Call score method
    result = metric.score(conversation=simple_conversation)

    # With no frustration, the score should be 0.0
    assert result.name == "test_frustration"
    assert result.value == 0.0
    assert result.reason == "The conversation shows no signs of user frustration."


@pytest.mark.asyncio
async def test_score__with_no_frustration__async(mock_model, simple_conversation):
    """Test scoring with no user frustration."""
    # Mock model response to return no as verdicts (no frustration)
    mock_model.agenerate_string.side_effect = _no_frustration_side_effect

    metric = UserFrustrationMetric(
        model=mock_model,
        name="test_frustration",
        include_reason=True,
        window_size=2,
        track=False,
    )
    # Call score method
    result = await metric.ascore(conversation=simple_conversation)

    # With no frustration, the score should be 0.0
    assert result.name == "test_frustration"
    assert result.value == 0.0
    assert result.reason == "The conversation shows no signs of user frustration."


def _mixed_frustration_side_effect(*args, **kwargs):
    response_format = kwargs.get("response_format")
    llm_input = kwargs.get("input")
    if response_format == schema.EvaluateUserFrustrationResponse:
        # For the call with frustrated response
        if "Both. Just give me a basic example." in llm_input:
            return json.dumps(
                {
                    "verdict": "yes",
                    "reason": "The user expresses frustration because the LLM's response didn't meet their expectations.",
                }
            )
        # For other calls (no frustration)
        return json.dumps({"verdict": "no"})
    elif response_format == schema.ScoreReasonResponse:
        return json.dumps(
            {
                "reason": "The score is 0.25 because the user showed frustration in one of their messages."
            }
        )
    return "{}"


def test_score__with_mixed_frustration(mock_model, frustrated_conversation):
    """Test scoring with a mix of frustrated and non-frustrated responses."""

    # Mock model response to alternate between yes and no
    mock_model.generate_string.side_effect = _mixed_frustration_side_effect

    metric = UserFrustrationMetric(
        model=mock_model,
        name="test_frustration",
        include_reason=True,
        window_size=2,
        track=False,
    )
    # Call score method
    result = metric.score(conversation=frustrated_conversation)

    # With some frustration, the score should be 0.25
    assert result.name == "test_frustration"
    assert result.value == 0.25
    assert "frustration" in result.reason.lower()


@pytest.mark.asyncio
async def test_score__with_mixed_frustration__async(
    mock_model, frustrated_conversation
):
    """Test scoring with a mix of frustrated and non-frustrated responses."""
    # Mock model response to alternate between yes and no
    mock_model.agenerate_string.side_effect = _mixed_frustration_side_effect

    metric = UserFrustrationMetric(
        model=mock_model,
        name="test_frustration",
        include_reason=True,
        window_size=2,
        track=False,
    )
    # Call score method
    result = await metric.ascore(conversation=frustrated_conversation)

    # With some frustration, the score should be 0.25
    assert result.name == "test_frustration"
    assert result.value == 0.25
    assert "frustration" in result.reason.lower()


def test_score_with_no_reason(mock_model):
    """Test scoring with include_reason=False."""
    conversation = [
        {"role": "user", "content": "Hello!"},
        {"role": "assistant", "content": "Hi there!"},
        {"role": "user", "content": "Thanks!"},
    ]

    # Create a new metric with include_reason=False
    metric = UserFrustrationMetric(model=mock_model, include_reason=False, track=False)

    mock_model.generate_string.return_value = json.dumps(
        {
            "verdict": "no",
        }
    )

    result = metric.score(conversation=conversation)
    assert result.name == "user_frustration_score"
    assert result.value == 0.0
    assert result.reason is None


@pytest.mark.asyncio
async def test_score_with_no_reason__async(mock_model):
    """Test scoring with include_reason=False."""
    conversation = [
        {"role": "user", "content": "Hello!"},
        {"role": "assistant", "content": "Hi there!"},
        {"role": "user", "content": "Thanks!"},
    ]

    # Create a new metric with include_reason=False
    metric = UserFrustrationMetric(model=mock_model, include_reason=False, track=False)

    mock_model.agenerate_string.return_value = json.dumps(
        {
            "verdict": "no",
        }
    )

    result = await metric.ascore(conversation=conversation)
    assert result.name == "user_frustration_score"
    assert result.value == 0.0
    assert result.reason is None


def test_score__with_model_validation_error_in_evaluation__raises_MetricComputationError(
    mock_model, simple_conversation
):
    """Test handling of validation errors in the evaluation response."""

    # Return invalid JSON to trigger validation error
    mock_model.generate_string.return_value = json.dumps(
        {"invalid_field": "This will cause a validation error"}
    )

    metric = UserFrustrationMetric(model=mock_model, include_reason=False, track=False)
    with pytest.raises(exceptions.MetricComputationError):
        metric.score(conversation=simple_conversation)


@pytest.mark.asyncio
async def test_score__with_model_validation_error_in_evaluation__async(
    mock_model, simple_conversation
):
    """Test handling of validation errors in the evaluation response."""

    # Return invalid JSON to trigger validation error
    mock_model.agenerate_string.return_value = json.dumps(
        {"invalid_field": "This will cause a validation error"}
    )

    metric = UserFrustrationMetric(model=mock_model, include_reason=False, track=False)
    with pytest.raises(exceptions.MetricComputationError):
        await metric.ascore(conversation=simple_conversation)


def test_score__empty_conversation__raises_MetricComputationError(mock_model):
    """Test scoring with an empty conversation."""
    conversation = []
    metric = UserFrustrationMetric(model=mock_model, include_reason=False, track=False)
    with pytest.raises(exceptions.MetricComputationError):
        metric.score(conversation=conversation)


@pytest.mark.asyncio
async def test_score__empty_conversation__raises_MetricComputationError__async(
    mock_model,
):
    """Test scoring with an empty conversation."""
    conversation = []
    metric = UserFrustrationMetric(model=mock_model, include_reason=False, track=False)
    with pytest.raises(exceptions.MetricComputationError):
        await metric.ascore(conversation=conversation)


def test_score__no_user_assistant_turns__raises_MetricComputationError(mock_model):
    """Test scoring with a conversation that has no valid user-assistant turns."""
    conversation = [
        {"role": "unknown", "content": "Hello!"},
        {"role": "someone", "content": "Hi there!"},
    ]
    metric = UserFrustrationMetric(model=mock_model, include_reason=False, track=False)
    with pytest.raises(exceptions.MetricComputationError):
        metric.score(conversation=conversation)


@pytest.mark.asyncio
async def test_score__no_user_assistant_turns__raises_MetricComputationError__async(
    mock_model,
):
    """Test scoring with a conversation that has no valid user-assistant turns."""
    conversation = [
        {"role": "unknown", "content": "Hello!"},
        {"role": "someone", "content": "Hi there!"},
    ]
    metric = UserFrustrationMetric(model=mock_model, include_reason=False, track=False)
    with pytest.raises(exceptions.MetricComputationError):
        await metric.ascore(conversation=conversation)


def _all_frustrated_side_effect(*args, **kwargs):
    response_format = kwargs.get("response_format")
    if response_format == schema.EvaluateUserFrustrationResponse:
        return json.dumps(
            {
                "verdict": "yes",
                "reason": "The user is showing clear signs of frustration.",
            }
        )
    elif response_format == schema.ScoreReasonResponse:
        return json.dumps(
            {"reason": "The score is 0.0 because all user messages show frustration."}
        )
    return "{}"


def test_score__with_all_frustrated_responses(mock_model):
    """Test scoring with all user messages showing frustration."""
    conversation = [
        {"role": "user", "content": "Why isn't this working?"},
        {"role": "assistant", "content": "I can help troubleshoot. What's the issue?"},
        {"role": "user", "content": "This is so frustrating! Nothing works!"},
    ]

    # Mock model response to return yes for all verdicts (all frustrated)
    mock_model.generate_string.side_effect = _all_frustrated_side_effect

    metric = UserFrustrationMetric(
        model=mock_model,
        name="test_frustration",
        include_reason=True,
        window_size=2,
        track=False,
    )
    # Call score method
    result = metric.score(conversation=conversation)

    # With all frustrated responses, the score should be 1.0
    assert result.name == "test_frustration"
    assert result.value == 1.0
    assert "frustration" in result.reason.lower()


@pytest.mark.asyncio
async def test_score__with_all_frustrated_responses__async(mock_model):
    """Test scoring with all user messages showing frustration."""
    conversation = [
        {"role": "user", "content": "Why isn't this working?"},
        {"role": "assistant", "content": "I can help troubleshoot. What's the issue?"},
        {"role": "user", "content": "This is so frustrating! Nothing works!"},
    ]

    # Mock model response to return yes for all verdicts (all frustrated)
    mock_model.agenerate_string.side_effect = _all_frustrated_side_effect

    metric = UserFrustrationMetric(
        model=mock_model,
        name="test_frustration",
        include_reason=True,
        window_size=2,
        track=False,
    )
    # Call score method
    result = await metric.ascore(conversation=conversation)

    # With all frustrated responses, the score should be 1.0
    assert result.name == "test_frustration"
    assert result.value == 1.0
    assert "frustration" in result.reason.lower()
