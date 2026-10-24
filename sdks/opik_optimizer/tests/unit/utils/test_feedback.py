from opik_optimizer.utils.feedback import format_evaluation_feedback, sanitize_feedback


def test_media_payloads_are_hidden_but_custom_dataset_fields_and_reasons_remain():
    item = {
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


def test_embedded_media_strings_are_redacted():
    assert (
        sanitize_feedback("before data:audio/wav;base64,aGVsbG8= after")
        == "before [media] after"
    )
