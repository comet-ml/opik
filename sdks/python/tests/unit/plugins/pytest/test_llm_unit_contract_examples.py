import json

import pytest


def structured_answer(user_message: str):
    normalized = user_message.lower()
    payload = {
        "answer": "unknown",
        "confidence": 0.0,
        "policy_flags": [],
    }
    if "refund" in normalized:
        payload["answer"] = "refund_policy"
        payload["confidence"] = 0.92
    elif "shipping" in normalized:
        payload["answer"] = "shipping_policy"
        payload["confidence"] = 0.87
    return payload


def is_schema_valid(payload):
    if not isinstance(payload, dict):
        return False
    required_keys = {"answer", "confidence", "policy_flags"}
    if set(payload.keys()) != required_keys:
        return False
    if not isinstance(payload["answer"], str):
        return False
    if not isinstance(payload["confidence"], float):
        return False
    if not isinstance(payload["policy_flags"], list):
        return False
    return True


@pytest.mark.parametrize(
    "test_input,expected_output,test_metadata",
    [
        (
            {"question": "What is your refund policy?"},
            {"expected_answer": "refund_policy", "min_confidence": 0.8},
            {"scenario": "refund_contract_v1", "suite": "contract"},
        ),
        (
            {"question": "How does shipping work?"},
            {"expected_answer": "shipping_policy", "min_confidence": 0.8},
            {"scenario": "shipping_contract_v1", "suite": "contract"},
        ),
    ],
)
def test_structured_answer__contract_schema_and_confidence__happyflow(
    test_input,
    expected_output,
    test_metadata,
):
    _ = test_metadata
    result = structured_answer(test_input["question"])
    assert is_schema_valid(result)
    assert result["answer"] == expected_output["expected_answer"]
    assert result["confidence"] >= expected_output["min_confidence"]
    assert json.dumps(result, sort_keys=True)
