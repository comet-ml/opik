from typing import Any

from opik_optimizer.utils.feedback import format_evaluation_feedback


def test_media_payloads_are_hidden_but_custom_dataset_fields_and_reasons_remain() -> (
    None
):
    item: dict[str, Any] = {
        "ticket": "Printer broken",
        "attachment": "data:image/png;base64,aGVsbG8=",
        "parts": [{"type": "image_url", "image_url": {"url": "{image}"}}],
    }
    text = format_evaluation_feedback(
        item, "Try restarting", [{"value": 0.0, "reason": "Missing escalation"}]
    )
    assert "Printer broken" in text
    assert "Missing escalation" in text
    assert "aGVsbG8=" not in text
    assert "[media]" in text
    assert item["attachment"].endswith("aGVsbG8=")


def test_input_fields_drop_labels_and_cap_values() -> None:
    from opik_optimizer.utils.feedback import input_fields

    fields = input_fields(
        {
            "id": "1",
            "question": "Q?",
            "answer": "A",
            "expected_output": "A",
            "gold_label": "A",
            "context": "X" * 20_000,
        }
    )
    assert set(fields) == {"question", "context"}
    assert fields["question"] == "Q?"
    assert len(fields["context"]) < 500


def test_redact_inline_media_keeps_structure_and_placeholders() -> None:
    from opik_optimizer.utils.multimodal import redact_inline_media

    messages: list[dict[str, Any]] = [
        {
            "role": "user",
            "content": [
                {"type": "text", "text": "Q: {question}"},
                {"type": "image_url", "image_url": {"url": "{image}"}},
                {
                    "type": "image_url",
                    "image_url": {"url": "data:image/png;base64,QUJD"},
                },
            ],
        }
    ]
    redacted = redact_inline_media(messages)
    assert redacted[0]["content"][:2] == messages[0]["content"][:2]
    assert redacted[0]["content"][2] == {
        "type": "image_url",
        "image_url": {"url": "[media]"},
    }
    assert messages[0]["content"][2]["image_url"]["url"].endswith("QUJD")
