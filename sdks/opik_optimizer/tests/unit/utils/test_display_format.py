from typing import Any

import pytest

from opik_optimizer import ChatPrompt
from opik_optimizer.utils.display.format import format_float
from opik_optimizer.utils.display.format import format_prompt_for_plaintext


@pytest.mark.parametrize(
    "value,digits,expected",
    [
        (3.14159265, None, "3.141593"),
        (3.14159265, 2, "3.14"),
        (0.0, None, "0.000000"),
        (-1.5, 3, "-1.500"),
        ("string_value", None, "string_value"),
        (42, None, "42"),
        (None, None, "None"),
    ],
)
def test_format_float_formats_values(
    value: Any, digits: int | None, expected: str
) -> None:
    if digits is None:
        result = format_float(value)
    else:
        result = format_float(value, digits=digits)
    assert result == expected


def test_format_prompt_for_plaintext_formats_single_prompt() -> None:
    prompt = ChatPrompt(system="You are helpful.", user="What is 2+2?")
    rendered = format_prompt_for_plaintext(prompt)
    assert "system:" in rendered
    assert "user:" in rendered
    assert "You are helpful." in rendered
    assert "What is 2+2?" in rendered


def test_format_prompt_for_plaintext_handles_prompt_dict(
    simple_chat_prompt: Any,
) -> None:
    prompt_dict = {"main": simple_chat_prompt}
    rendered = format_prompt_for_plaintext(prompt_dict)
    assert "[main]" in rendered
    assert "system" in rendered or "user" in rendered


def test_format_prompt_for_plaintext_truncates_long_content() -> None:
    long_content = "x" * 500
    prompt = ChatPrompt(system=long_content, user="short")
    rendered = format_prompt_for_plaintext(prompt)
    assert len(rendered) < 500


def test_format_prompt_for_plaintext_handles_multimodal_content() -> None:
    prompt = ChatPrompt(
        messages=[
            {"role": "system", "content": "Analyze image."},
            {
                "role": "user",
                "content": [
                    {"type": "text", "text": "What is this?"},
                    {
                        "type": "image_url",
                        "image_url": {"url": "data:image/png;base64,abc"},
                    },
                ],
            },
        ]
    )
    rendered = format_prompt_for_plaintext(prompt)
    assert "[multimodal content]" in rendered
