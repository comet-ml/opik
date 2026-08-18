import json
from unittest import mock

import pytest

from opik import exceptions
from opik.evaluation.metrics.conversation import helpers as conversation_helpers
from opik.evaluation.metrics.conversation.llm_judges.conversational_coherence import (
    schema,
    templates,
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


def _assistant_message(content: str) -> dict:
    return {"role": "assistant", "content": content}


def _all_relevant_responses_side_effect(*args, **kwargs):
    response_format = kwargs.get("response_format")
    if response_format == schema.EvaluateConversationCoherenceResponse:
        return _assistant_message(json.dumps({"verdict": "yes", "reason": None}))
    elif response_format == schema.ScoreReasonResponse:
        return _assistant_message(
            json.dumps(
                {"reason": "The conversation successfully addressed user goals."}
            )
        )
    return _assistant_message("{}")


def test_score__with_all_relevant_responses(mock_model, simple_conversation):
    """Test scoring with all LLM responses being relevant."""

    # Mock model response to return yes as verdicts
    mock_model.generate_chat_completion.side_effect = (
        _all_relevant_responses_side_effect
    )

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
    mock_model.agenerate_chat_completion.side_effect = (
        _all_relevant_responses_side_effect
    )

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
    messages = kwargs.get("messages") or []
    llm_input = "\n".join(m["content"] for m in messages)
    if response_format == schema.EvaluateConversationCoherenceResponse:
        # For the 2nd call (irrelevant response)
        if "I like cats" in llm_input:
            return _assistant_message(
                json.dumps(
                    {
                        "verdict": "no",
                        "reason": "The LLM response about liking cats is irrelevant to the weather question.",
                    }
                )
            )
        # For the 1st call (relevant response)
        return _assistant_message(json.dumps({"verdict": "yes", "reason": None}))
    elif response_format == schema.ScoreReasonResponse:
        return _assistant_message(
            json.dumps(
                {
                    "reason": "The score is 0.5 because one of the responses was irrelevant."
                }
            )
        )
    return _assistant_message("{}")


def test_score__with_mixed_relevance(mock_model, irrelevant_conversation):
    """Test scoring with a mix of relevant and irrelevant responses."""

    # Mock model response to alternate between yes and no
    mock_model.generate_chat_completion.side_effect = _mixed_relevance_side_effect

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
    mock_model.agenerate_chat_completion.side_effect = _mixed_relevance_side_effect

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

    mock_model.generate_chat_completion.return_value = _assistant_message(
        json.dumps({"verdict": "yes", "reason": None})
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

    mock_model.agenerate_chat_completion.return_value = _assistant_message(
        json.dumps({"verdict": "yes", "reason": None})
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
    mock_model.generate_chat_completion.return_value = _assistant_message(
        json.dumps({"invalid_field": "This will cause a validation error"})
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
    mock_model.agenerate_chat_completion.return_value = _assistant_message(
        json.dumps({"invalid_field": "This will cause a validation error"})
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


@pytest.fixture
def grounded_conversation():
    return [
        {"role": "user", "content": "What is the overdraft fee?"},
        {
            "role": "assistant",
            "content": "It is 5% of the overdrawn amount.",
            "context": ["The overdraft fee is 5% of the overdrawn amount."],
        },
        {"role": "user", "content": "How long do I have to repay?"},
        {
            "role": "assistant",
            "content": "You can repay whenever you like.",
            "context": ["Customers must repay an overdraft within 30 days."],
        },
    ]


def _make_with_documents_side_effect():
    """First window relevant+supported, second one not."""
    verdicts = iter(["yes", "no"])

    def side_effect(*args, **kwargs):
        response_format = kwargs.get("response_format")
        if response_format == schema.EvaluateConversationCoherenceResponse:
            verdict = next(verdicts)
            return _assistant_message(
                json.dumps(
                    {
                        "verdict": verdict,
                        "reason": None
                        if verdict == "yes"
                        else "Contradicts the documents.",
                    }
                )
            )
        elif response_format == schema.ScoreReasonResponse:
            return _assistant_message(
                json.dumps({"reason": "Because of the documents."})
            )
        return _assistant_message("{}")

    return side_effect


def test_score__messages_with_context__documents_folded_into_the_single_score(
    mock_model, grounded_conversation
):
    """Documents make the verdict stricter, but the metric still returns one score."""
    mock_model.generate_chat_completion.side_effect = _make_with_documents_side_effect()

    metric = ConversationalCoherenceMetric(model=mock_model, track=False)
    result = metric.score(grounded_conversation)

    assert not isinstance(result, list)
    assert result.name == "conversational_coherence_score"
    assert result.value == 0.5  # one of two windows judged relevant AND supported

    used_formats = {
        call.kwargs.get("response_format")
        for call in mock_model.generate_chat_completion.call_args_list
    }
    assert schema.EvaluateConversationCoherenceResponse in used_formats


def test_score__messages_with_context__uses_the_with_documents_prompt(
    mock_model, grounded_conversation
):
    """The documents have to actually reach the judge."""
    mock_model.generate_chat_completion.side_effect = _make_with_documents_side_effect()

    ConversationalCoherenceMetric(model=mock_model, track=False).score(
        grounded_conversation
    )

    prompts = [
        call.kwargs["messages"][1]["content"]
        for call in mock_model.generate_chat_completion.call_args_list
        if call.kwargs.get("response_format")
        == schema.EvaluateConversationCoherenceResponse
    ]
    assert any("Retrieved documents" in prompt for prompt in prompts)
    assert any(
        "The overdraft fee is 5% of the overdrawn amount." in prompt
        for prompt in prompts
    )


def test_score__messages_without_context__uses_the_original_prompt(
    mock_model, simple_conversation
):
    """Without documents the metric behaves exactly as before."""
    mock_model.generate_chat_completion.side_effect = (
        _all_relevant_responses_side_effect
    )

    metric = ConversationalCoherenceMetric(model=mock_model, track=False)
    result = metric.score(simple_conversation)

    assert not isinstance(result, list)
    assert result.name == "conversational_coherence_score"
    prompts = [
        call.kwargs["messages"][0]["content"]
        for call in mock_model.generate_chat_completion.call_args_list
    ]
    assert not any("RETRIEVED DOCUMENTS" in prompt for prompt in prompts)


def test_prompts__never_leak_context_into_the_turns():
    """Retrieved documents must not reach a judge prompt by stringifying messages.

    A window whose LAST agent message has no documents takes the no-documents path,
    but may still contain an EARLIER turn that carries them - that leaked before the
    prompts rendered role/content explicitly.
    """
    conversation = [
        {"role": "user", "content": "What is the overdraft fee?"},
        {"role": "assistant", "content": "5%.", "context": ["SECRET-DOC"]},
        {"role": "user", "content": "Thanks!"},
        {"role": "assistant", "content": "You are welcome."},
    ]
    window = conversation_helpers.extract_turns_windows_from_conversation(
        conversation=conversation, window_size=10
    )[-1]

    no_documents_prompt = templates.build_evaluate_conversation_messages(
        sliding_window=window
    )[1]["content"]
    assert "SECRET-DOC" not in no_documents_prompt

    with_documents_prompt = (
        templates.build_evaluate_conversation_with_documents_messages(
            sliding_window=window, retrieved_documents=["THE-DOC"]
        )[1]["content"]
    )
    # Documents appear only in their own section, never inside the turns.
    assert "SECRET-DOC" not in with_documents_prompt
    assert "THE-DOC" in with_documents_prompt
