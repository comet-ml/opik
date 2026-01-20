from typing import Any

from opik_optimizer.utils.display.format import format_prompt_for_plaintext


def test_format_prompt_for_plaintext_handles_prompt_dict(
    simple_chat_prompt: Any,
) -> None:
    prompt_dict = {"main": simple_chat_prompt}
    rendered = format_prompt_for_plaintext(prompt_dict)
    assert "[main]" in rendered
    assert "system" in rendered or "user" in rendered
