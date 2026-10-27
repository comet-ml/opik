from opik_optimizer.api_objects.chat_prompt import ChatPrompt


def test_str_truncates_long_messages() -> None:
    long_user = "a" * 600
    prompt = ChatPrompt(user=long_user)

    prompt_str = str(prompt)

    assert prompt_str.endswith("...")
    # Allow for JSON structure overhead (quotes, brackets, etc.)
    assert len(prompt_str) <= ChatPrompt.DISPLAY_TRUNCATION_LENGTH + 20
