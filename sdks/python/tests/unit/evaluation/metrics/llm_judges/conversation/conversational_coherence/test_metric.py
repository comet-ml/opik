import json
from unittest import mock

import pytest

from opik import exceptions
from opik.evaluation.metrics.conversation.llm_judges.conversational_coherence import (
    schema,
)
from opik.evaluation.metrics.conversation.llm_judges.conversational_coherence.metric import (
    ConversationalCoherenceMetric,
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
    ]


@pytest.fixture
def irrelevant_conversation():
    return [
        {"role": "user", "content": "Hello!"},
        {"role": "assistant", "content": "Hi there! How can I help you?"},
        {"role": "user", "content": "What's the weather like?"},
        {"role": "assistant", "content": "I like cats."},  # Irrelevant
    ]


@pytest.fixture
def mock_model():
    model = mock.MagicMock(spec=base_model.OpikBaseModel)
    return model


def _all_relevant_responses_side_effect(*args, **kwargs):
    response_format = kwargs.get("response_format")
    if response_format == schema.EvaluateConversationCoherenceResponse:
        return json.dumps({"verdict": "yes"})
    elif response_format == schema.ScoreReasonResponse:
        return json.dumps(
            {"reason": "The conversation successfully addressed user goals."}
        )
    return "{}"


def test_score__with_all_relevant_responses(mock_model, simple_conversation):
    """Test scoring with all LLM responses being relevant."""

    # Mock model response to return yes as verdicts
    mock_model.generate_string.side_effect = _all_relevant_responses_side_effect

    metric = ConversationalCoherenceMetric(
        model=mock_model,
        name="test_coherence",
        include_reason=True,
        window_size=2,
        track=False,
    )
    # Call score method
    result = metric.score(conversation=simple_conversation)

    # With all responses relevant, the score should be 1.0
    assert result.name == "test_coherence"
    assert result.value == 1.0
    assert result.reason == "The conversation successfully addressed user goals."


@pytest.mark.asyncio
async def test_score__with_all_relevant_responses__async(
    mock_model, simple_conversation
):
    """Test scoring with all LLM responses being relevant."""
    # Mock model response to return yes as verdicts
    mock_model.agenerate_string.side_effect = _all_relevant_responses_side_effect

    metric = ConversationalCoherenceMetric(
        model=mock_model,
        name="test_coherence",
        include_reason=True,
        window_size=2,
        track=False,
    )
    # Call score method
    result = await metric.ascore(conversation=simple_conversation)

    # With all responses relevant, the score should be 1.0
    assert result.name == "test_coherence"
    assert result.value == 1.0
    assert result.reason == "The conversation successfully addressed user goals."


def _mixed_relevance_side_effect(*args, **kwargs):
    response_format = kwargs.get("response_format")
    llm_input = kwargs.get("input")
    if response_format == schema.EvaluateConversationCoherenceResponse:
        # For the 2nd call (irrelevant response)
        if "I like cats" in llm_input:
            return json.dumps(
                {
                    "verdict": "no",
                    "reason": "The LLM response about liking cats is irrelevant to the weather question.",
                }
            )
        # For the 1st call (relevant response)
        return json.dumps({"verdict": "yes"})
    elif response_format == schema.ScoreReasonResponse:
        return json.dumps(
            {"reason": "The score is 0.5 because one of the responses was irrelevant."}
        )
    return "{}"


def test_score__with_mixed_relevance(mock_model, irrelevant_conversation):
    """Test scoring with a mix of relevant and irrelevant responses."""

    # Mock model response to alternate between yes and no
    mock_model.generate_string.side_effect = _mixed_relevance_side_effect

    metric = ConversationalCoherenceMetric(
        model=mock_model,
        name="test_coherence",
        include_reason=True,
        window_size=2,
        track=False,
    )
    # Call score method
    result = metric.score(conversation=irrelevant_conversation)

    # With half of the responses relevant, the score should be 0.5
    assert result.name == "test_coherence"
    assert result.value == 0.5
    assert (
        result.reason == "The score is 0.5 because one of the responses was irrelevant."
    )


@pytest.mark.asyncio
async def test_score__with_mixed_relevance__async(mock_model, irrelevant_conversation):
    """Test scoring with a mix of relevant and irrelevant responses."""
    # Mock model response to alternate between yes and no
    mock_model.agenerate_string.side_effect = _mixed_relevance_side_effect

    metric = ConversationalCoherenceMetric(
        model=mock_model,
        name="test_coherence",
        include_reason=True,
        window_size=2,
        track=False,
    )
    # Call score method
    result = await metric.ascore(conversation=irrelevant_conversation)

    # With half of the responses relevant, the score should be 0.5
    assert result.name == "test_coherence"
    assert result.value == 0.5
    assert (
        result.reason == "The score is 0.5 because one of the responses was irrelevant."
    )


def test_score_with_no_reason(mock_model):
    """Test scoring with include_reason=False."""
    conversation = [
        {"role": "user", "content": "Hello!"},
        {"role": "assistant", "content": "Hi there!"},
    ]

    # Create a new metric with include_reason=False
    metric = ConversationalCoherenceMetric(
        model=mock_model, include_reason=False, track=False
    )

    mock_model.generate_string.return_value = json.dumps(
        {
            "verdict": "yes",
        }
    )

    result = metric.score(conversation=conversation)
    assert result.name == "conversational_coherence_score"
    assert result.value == 1.0
    assert result.reason is None


@pytest.mark.asyncio
async def test_score_with_no_reason__async(mock_model):
    """Test scoring with include_reason=False."""
    conversation = [
        {"role": "user", "content": "Hello!"},
        {"role": "assistant", "content": "Hi there!"},
    ]

    # Create a new metric with include_reason=False
    metric = ConversationalCoherenceMetric(
        model=mock_model, include_reason=False, track=False
    )

    mock_model.agenerate_string.return_value = json.dumps(
        {
            "verdict": "yes",
        }
    )

    result = await metric.ascore(conversation=conversation)
    assert result.name == "conversational_coherence_score"
    assert result.value == 1.0
    assert result.reason is None


def test_score__with_model_validation_error_in_evaluation__raises_MetricComputationError(
    mock_model, simple_conversation
):
    """Test handling of validation errors in the evaluation response."""

    # Return invalid JSON to trigger validation error
    mock_model.generate_string.return_value = json.dumps(
        {"invalid_field": "This will cause a validation error"}
    )

    metric = ConversationalCoherenceMetric(
        model=mock_model, include_reason=False, track=False
    )
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

    metric = ConversationalCoherenceMetric(
        model=mock_model, include_reason=False, track=False
    )
    with pytest.raises(exceptions.MetricComputationError):
        await metric.ascore(conversation=simple_conversation)


def test_score__empty_conversation__raises_MetricComputationError(mock_model):
    """Test scoring with an empty conversation."""
    conversation = [
        {"role": "unknown", "content": "Hello!"},
        {"role": "someone", "content": "Hi there!"},
    ]
    metric = ConversationalCoherenceMetric(
        model=mock_model, include_reason=False, track=False
    )
    with pytest.raises(exceptions.MetricComputationError):
        metric.score(conversation=conversation)


@pytest.mark.asyncio
async def test_score__empty_conversation__raises_MetricComputationError__async(
    mock_model,
):
    """Test scoring with an empty conversation."""
    conversation = [
        {"role": "unknown", "content": "Hello!"},
        {"role": "someone", "content": "Hi there!"},
    ]
    metric = ConversationalCoherenceMetric(
        model=mock_model, include_reason=False, track=False
    )
    with pytest.raises(exceptions.MetricComputationError):
        await metric.ascore(conversation=conversation)


def test_score__no_user_assistant_turns__raises_MetricComputationError(mock_model):
    """Test scoring with an empty conversation."""
    conversation = []
    metric = ConversationalCoherenceMetric(
        model=mock_model, include_reason=False, track=False
    )
    with pytest.raises(exceptions.MetricComputationError):
        metric.score(conversation=conversation)


@pytest.mark.asyncio
async def test_score__no_user_assistant_turns__raises_MetricComputationError__async(
    mock_model,
):
    """Test scoring with an empty conversation."""
    conversation = []
    metric = ConversationalCoherenceMetric(
        model=mock_model, include_reason=False, track=False
    )
    with pytest.raises(exceptions.MetricComputationError):
        await metric.ascore(conversation=conversation)
