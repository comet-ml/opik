import pytest

from opik import llm_unit, track


@track
def classify_intent(user_message: str) -> str:
    normalized = user_message.lower()
    if "refund" in normalized:
        return "refund_request"
    if "shipping" in normalized:
        return "shipping_question"
    return "unknown"


@llm_unit(expected_output_key="expected_intent", input_key="payload")
@pytest.mark.parametrize(
    "payload,expected_intent",
    [
        ({"message": "I want a refund"}, "refund_request"),
        ({"message": "Where is shipping status?"}, "shipping_question"),
    ],
)
def test_intent_classifier(payload, expected_intent):
    prediction = classify_intent(payload["message"])
    assert prediction == expected_intent
