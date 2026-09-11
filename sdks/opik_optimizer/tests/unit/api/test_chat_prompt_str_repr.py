from opik_optimizer.api_objects.chat_prompt import ChatPrompt


def test_str_serializes_messages() -> None:
    prompt = ChatPrompt(system="Stay concise.", user="{question}")

    prompt_str = str(prompt)

    assert "Stay concise." in prompt_str
    assert "{question}" in prompt_str
    assert "ChatPrompt object at" not in prompt_str

    # Default name should show up in repr
    assert "chat-prompt" in repr(prompt)


def test_repr_includes_custom_name() -> None:
    prompt = ChatPrompt(name="my-prompt", system="Stay concise.", user="{question}")
    repr_str = repr(prompt)
    assert "my-prompt" in repr_str
    assert "ChatPrompt" in repr_str


def test_str_truncates_long_messages() -> None:
    long_user = "a" * 600
    prompt = ChatPrompt(user=long_user)

    prompt_str = str(prompt)

    assert prompt_str.endswith("...")
    # Allow for JSON structure overhead (quotes, brackets, etc.)
    assert len(prompt_str) <= ChatPrompt.DISPLAY_TRUNCATION_LENGTH + 20
