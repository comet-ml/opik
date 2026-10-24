from copy import deepcopy

import pytest

from opik_optimizer import ChatPrompt
from opik_optimizer.api_objects.types import (
    extract_text_from_content,
    rebuild_content_with_new_text,
)
from opik_optimizer.utils.prompt_roles import derive_prompt


@pytest.mark.parametrize(
    "media",
    [
        {"type": "image_url", "image_url": {"url": "{image}"}},
        {"type": "image_url", "image_url": {"url": "data:image/png;base64,example"}},
    ],
)
def test_rewrite_keeps_media_and_prompt_configuration(media):
    original = [
        {"role": "system", "content": "Keep"},
        {
            "role": "user",
            "content": [
                {"type": "text", "text": "First"},
                media,
                {"type": "text", "text": "Second"},
            ],
        },
    ]
    before = deepcopy(original)
    base = ChatPrompt(
        name="vision",
        messages=original,
        model="agent-model",
        model_parameters={"temperature": 0.3},
    )
    result = derive_prompt(
        base,
        [
            {"role": "system", "content": "Changed"},
            {"role": "user", "content": "Rewritten"},
        ],
        {"user"},
    )
    messages = result.get_messages()
    assert messages[0]["content"] == "Keep"
    assert messages[1]["content"][1] == media
    assert extract_text_from_content(messages[1]["content"]).strip() == "Rewritten"
    assert result.name == "vision"
    assert result.model == "agent-model"
    assert result.model_kwargs == {"temperature": 0.3}
    assert base.get_messages() == before
    messages[1]["content"][1][next(key for key in media if key != "type")].clear()
    assert base.get_messages() == before


def test_multitext_rewrite_is_inserted_once_and_extraction_keeps_all_text():
    content = [
        {"type": "text", "text": "First"},
        {"type": "image_url", "image_url": {"url": "{image}"}},
        {"type": "text", "text": "Second"},
    ]
    assert extract_text_from_content(content) == "First\nSecond"
    rewritten = rebuild_content_with_new_text(content, "Whole rewrite")
    assert [part.get("text") for part in rewritten if part["type"] == "text"] == [
        "Whole rewrite",
        "",
    ]
    assert content[-1]["text"] == "Second"


def test_text_only_rewrite_input_keeps_all_text_and_excludes_media():
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
