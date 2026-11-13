from opik_optimizer import ChatPrompt
from opik_optimizer.utils.display.format import format_prompt_for_plaintext
from tests.unit.fixtures import system_message, user_message


def test_format_prompt_for_plaintext_handles_multimodal_content() -> None:
    prompt = ChatPrompt(
        messages=[
            system_message("Analyze image."),
            user_message(
                [
                    {"type": "text", "text": "What is this?"},
                    {
                        "type": "image_url",
                        "image_url": {"url": "data:image/png;base64,abc"},
                    },
                ]
            ),
        ]
    )
    rendered = format_prompt_for_plaintext(prompt)
    assert "[multimodal content]" in rendered
