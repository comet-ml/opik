import pytest

from unittest.mock import MagicMock

from opik import exceptions
from opik.evaluation.metrics import SessionCompletenessQuality
from opik.evaluation.models import base_model
from tests.testlib import assert_helpers


@pytest.fixture
def simple_conversation():
    return [
        {
            "role": "user",
            "content": "Hello! I need help with two things: finding a recipe for chocolate cake and planning my weekend trip.",
        },
        {
            "role": "assistant",
            "content": "Hi there! I'd be happy to help with both. For chocolate cake, here's a simple recipe: [recipe details]. Now, regarding your weekend trip, what destination are you considering?",
        },
        {
            "role": "user",
            "content": "I'm thinking about going to the mountains. What should I pack?",
        },
        {
            "role": "assistant",
            "content": "For a mountain trip, I recommend packing layers of clothing, hiking boots, water bottle, sunscreen, and a first aid kit. The weather can change quickly in the mountains, so be prepared for various conditions.",
        },
    ]


@pytest.fixture
def incomplete_conversation():
    return [
        {
            "role": "user",
            "content": "I need help with my homework and planning a birthday party.",
        },
        {
            "role": "assistant",
            "content": "I can help with your homework. What subject are you working on?",
        },
        {"role": "user", "content": "It's math. I need to solve these equations."},
        {
            "role": "assistant",
            "content": "Let me help you with those math equations. First, you need to isolate the variable...",
        },
    ]


@pytest.fixture
def mock_model():
    model = MagicMock(spec=base_model.OpikBaseModel)
    return model


def test__session_completeness_quality__mocked__happy_path(
    simple_conversation, mock_model
):
    # Setup mock responses
    mock_model.generate_string.side_effect = [
        '{"user_goals": ["Find a chocolate cake recipe", "Plan a weekend trip to the mountains"]}',
        '{"verdict": "Yes", "reason": "The assistant provided a chocolate cake recipe as requested."}',
        '{"verdict": "Yes", "reason": "The assistant provided packing advice for the mountain trip."}',
        '{"reason": "The conversation successfully addressed both user goals: finding a chocolate cake recipe and planning a weekend trip to the mountains."}',
    ]

    metric = SessionCompletenessQuality(model=mock_model, track=False)
    result = metric.score(simple_conversation)

    assert_helpers.assert_score_result(result)
    assert result.value == 1.0  # Both goals were met
    assert (
        result.reason
        == "The conversation successfully addressed both user goals: finding a chocolate cake recipe and planning a weekend trip to the mountains."
    )

    # Verify the correct calls were made to the model
    assert mock_model.generate_string.call_count == 4


@pytest.mark.asyncio
async def test__session_completeness_quality__mocked__happy_path__async(
    simple_conversation, mock_model
):
    # Setup mock responses
    mock_model.agenerate_string.side_effect = [
        '{"user_goals": ["Find a chocolate cake recipe", "Plan a weekend trip to the mountains"]}',
        '{"verdict": "Yes", "reason": "The assistant provided a chocolate cake recipe as requested."}',
        '{"verdict": "Yes", "reason": "The assistant provided packing advice for the mountain trip."}',
        '{"reason": "The conversation successfully addressed both user goals: finding a chocolate cake recipe and planning a weekend trip to the mountains."}',
    ]

    metric = SessionCompletenessQuality(model=mock_model, track=False)
    result = await metric.ascore(simple_conversation)

    assert_helpers.assert_score_result(result)
    assert result.value == 1.0  # Both goals were met
    assert (
        result.reason
        == "The conversation successfully addressed both user goals: finding a chocolate cake recipe and planning a weekend trip to the mountains."
    )

    # Verify the correct calls were made to the model
    assert mock_model.agenerate_string.call_count == 4


def test__session_completeness_quality__mocked__partial_completion(
    incomplete_conversation, mock_model
):
    # Setup mock responses
    mock_model.generate_string.side_effect = [
        '{"user_goals": ["Get help with homework", "Plan a birthday party"]}',
        '{"verdict": "Yes", "reason": "The assistant helped with the math homework."}',
        '{"verdict": "No", "reason": "The assistant did not address the birthday party planning at all."}',
        '{"reason": "The conversation only addressed one of the two user goals. The assistant helped with math homework but did not provide any assistance with birthday party planning."}',
    ]

    metric = SessionCompletenessQuality(model=mock_model, track=False)
    result = metric.score(incomplete_conversation)

    assert_helpers.assert_score_result(result)
    assert result.value == 0.5  # Only one of two goals was met
    assert (
        result.reason
        == "The conversation only addressed one of the two user goals. The assistant helped with math homework but did not provide any assistance with birthday party planning."
    )

    # Verify the correct calls were made to the model
    assert mock_model.generate_string.call_count == 4


@pytest.mark.asyncio
async def test__session_completeness_quality__mocked__partial_completion__async(
    incomplete_conversation, mock_model
):
    # Setup mock responses
    mock_model.agenerate_string.side_effect = [
        '{"user_goals": ["Get help with homework", "Plan a birthday party"]}',
        '{"verdict": "Yes", "reason": "The assistant helped with the math homework."}',
        '{"verdict": "No", "reason": "The assistant did not address the birthday party planning at all."}',
        '{"reason": "The conversation only addressed one of the two user goals. The assistant helped with math homework but did not provide any assistance with birthday party planning."}',
    ]

    metric = SessionCompletenessQuality(model=mock_model, track=False)
    result = await metric.ascore(incomplete_conversation)

    assert_helpers.assert_score_result(result)
    assert result.value == 0.5  # Only one of two goals was met
    assert (
        result.reason
        == "The conversation only addressed one of the two user goals. The assistant helped with math homework but did not provide any assistance with birthday party planning."
    )

    # Verify the correct calls were made to the model
    assert mock_model.agenerate_string.call_count == 4


def test__session_completeness__mocked__quality_no_goals(mock_model):
    # Setup mock responses for a conversation with no clear goals
    conversation = [
        {"role": "user", "content": "Hi!"},
        {"role": "assistant", "content": "Hello! How can I help you today?"},
    ]

    mock_model.generate_string.side_effect = [
        '{"user_goals": []}',
        '{"reason": "No specific user goals were identified in this conversation."}',
    ]

    metric = SessionCompletenessQuality(model=mock_model, track=False)
    result = metric.score(conversation)

    assert_helpers.assert_score_result(result)
    assert result.value == 0.0  # No goals to meet
    assert (
        result.reason == "No specific user goals were identified in this conversation."
    )


@pytest.mark.asyncio
async def test__session_completeness__mocked__quality_no_goals__async(mock_model):
    # Setup mock responses for a conversation with no clear goals
    conversation = [
        {"role": "user", "content": "Hi!"},
        {"role": "assistant", "content": "Hello! How can I help you today?"},
    ]

    mock_model.agenerate_string.side_effect = [
        '{"user_goals": []}',
        '{"reason": "No specific user goals were identified in this conversation."}',
    ]

    metric = SessionCompletenessQuality(model=mock_model, track=False)
    result = await metric.ascore(conversation)

    assert_helpers.assert_score_result(result)
    assert result.value == 0.0  # No goals to meet
    assert (
        result.reason == "No specific user goals were identified in this conversation."
    )


def test__session_completeness_quality__mocked__without_reason(
    simple_conversation, mock_model
):
    # Setup mock responses
    mock_model.generate_string.side_effect = [
        '{"user_goals": ["Find a chocolate cake recipe", "Plan a weekend trip to the mountains"]}',
        '{"verdict": "Yes", "reason": "The assistant provided a chocolate cake recipe as requested."}',
        '{"verdict": "Yes", "reason": "The assistant provided packing advice for the mountain trip."}',
    ]

    metric = SessionCompletenessQuality(
        model=mock_model, include_reason=False, track=False
    )
    result = metric.score(simple_conversation)

    assert_helpers.assert_score_result(result, include_reason=False)
    assert result.value == 1.0  # Both goals were met
    assert result.reason is None  # No reason should be generated

    # Verify only 3 calls were made (no call for generating reason)
    assert mock_model.generate_string.call_count == 3


@pytest.mark.asyncio
async def test__session_completeness_quality__mocked__without_reason__async(
    simple_conversation, mock_model
):
    # Setup mock responses
    mock_model.agenerate_string.side_effect = [
        '{"user_goals": ["Find a chocolate cake recipe", "Plan a weekend trip to the mountains"]}',
        '{"verdict": "Yes", "reason": "The assistant provided a chocolate cake recipe as requested."}',
        '{"verdict": "Yes", "reason": "The assistant provided packing advice for the mountain trip."}',
    ]

    metric = SessionCompletenessQuality(
        model=mock_model, include_reason=False, track=False
    )
    result = await metric.ascore(simple_conversation)

    assert_helpers.assert_score_result(result, include_reason=False)
    assert result.value == 1.0  # Both goals were met
    assert result.reason is None  # No reason should be generated

    # Verify only 3 calls were made (no call for generating reason)
    assert mock_model.agenerate_string.call_count == 3


def test__session_completeness__mocked__model_error(simple_conversation, mock_model):
    # Setup mock to raise an exception
    mock_model.generate_string.side_effect = Exception("Model error")

    metric = SessionCompletenessQuality(model=mock_model, track=False)

    with pytest.raises(exceptions.MetricComputationError):
        metric.score(simple_conversation)


@pytest.mark.asyncio
async def test__session_completeness__mocked__model_error__async(
    simple_conversation, mock_model
):
    # Setup mock to raise an exception
    mock_model.agenerate_string.side_effect = Exception("Model error")

    metric = SessionCompletenessQuality(model=mock_model, track=False)

    with pytest.raises(exceptions.MetricComputationError):
        await metric.ascore(simple_conversation)


def test__session_completeness_quality__mocked__parsing_error(
    simple_conversation, mock_model
):
    # Setup mock to return invalid JSON
    mock_model.generate_string.return_value = "This is not valid JSON"

    metric = SessionCompletenessQuality(model=mock_model, track=False)

    with pytest.raises(exceptions.MetricComputationError):
        metric.score(simple_conversation)


@pytest.mark.asyncio
async def test__session_completeness_quality__mocked__parsing_error__async(
    simple_conversation, mock_model
):
    # Setup mock to return invalid JSON
    mock_model.generate_string.return_value = "This is not valid JSON"

    metric = SessionCompletenessQuality(model=mock_model, track=False)

    with pytest.raises(exceptions.MetricComputationError):
        await metric.ascore(simple_conversation)


def test__session_completeness_quality__empty_conversation__raises_error(mock_model):
    """Test scoring with an empty conversation."""
    conversation = []
    metric = SessionCompletenessQuality(
        model=mock_model, include_reason=False, track=False
    )
    with pytest.raises(exceptions.MetricComputationError):
        metric.score(conversation=conversation)


@pytest.mark.asyncio
async def test__session_completeness_quality__empty_conversation__raises_error__async(
    mock_model,
):
    """Test scoring with an empty conversation."""
    conversation = []
    metric = SessionCompletenessQuality(
        model=mock_model, include_reason=False, track=False
    )
    with pytest.raises(exceptions.MetricComputationError):
        await metric.ascore(conversation=conversation)
