from typing import Any


from opik_optimizer.api_objects.types import (
    extract_text_from_content,
    rebuild_content_with_new_text,
)


def test_multitext_rewrite_is_inserted_once_and_extraction_keeps_all_text() -> None:
    content: list[Any] = [
        {"type": "image_url", "image_url": {"url": "{a}"}},
        {"type": "text", "text": "First"},
        {"type": "image_url", "image_url": {"url": "{b}"}},
        {"type": "text", "text": "Second"},
    ]
    assert extract_text_from_content(content) == "First\nSecond"
    rewritten = rebuild_content_with_new_text(content, "Whole rewrite")
    assert rewritten == [
        {"type": "image_url", "image_url": {"url": "{a}"}},
        {"type": "text", "text": "Whole rewrite"},
        {"type": "image_url", "image_url": {"url": "{b}"}},
    ]
    assert content[-1]["text"] == "Second"
    assert rebuild_content_with_new_text(content, "First\nSecond") == content


def test_text_only_rewrite_input_keeps_all_text_and_excludes_media() -> None:
    from opik_optimizer.utils.multimodal import text_only_messages

    messages = [
        {
            "role": "user",
            "content": [
                {"type": "text", "text": "First"},
                {
                    "type": "image_url",
                    "image_url": {"url": "data:image/png;base64,example"},
                },
                {"type": "text", "text": "Second"},
            ],
        }
    ]
    assert text_only_messages(messages) == [
        {"role": "user", "content": "First\nSecond"}
    ]
    assert len(messages[0]["content"]) == 3
