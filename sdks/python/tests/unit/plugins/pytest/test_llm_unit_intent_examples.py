import pytest


def classify_intent(user_message: str) -> str:
    normalized = user_message.lower()
    if "refund" in normalized:
        return "refund_request"
    if "shipping" in normalized:
        return "shipping_question"
    return "unknown"


@pytest.mark.parametrize(
    "payload,expected_intent",
    [
        ({"message": "I want a refund"}, "refund_request"),
        ({"message": "Where is shipping status?"}, "shipping_question"),
    ],
)
def test_classify_intent__known_intents__happyflow(payload, expected_intent):
    prediction = classify_intent(payload["message"])
    assert prediction == expected_intent
