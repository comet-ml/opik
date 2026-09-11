import pytest

from opik.evaluation.metrics.conversation import helpers as conversation_helpers
from opik.evaluation.metrics.conversation import (
    conversation_turns_factory as conversation_turns,
)


def test_get_turns_in_sliding_window():
    """Test that the window_size parameter is correctly used."""
    conversation = [
        {"role": "user", "content": "Hello!"},
        {"role": "assistant", "content": "Hi there!"},
        {"role": "user", "content": "How are you?"},
        {"role": "assistant", "content": "I'm doing well!"},
    ]

    turns = conversation_turns.build_conversation_turns(conversation)

    window_generator = conversation_helpers.get_turns_in_sliding_window(
        turns, window_size=2
    )

    # Check that the first window has 1 turn and the second window has 2
    expected_size = 1
    for window in window_generator:
        assert len(window) == expected_size
        expected_size += 1


def test_extract_turns_windows_from_conversation__happy_path():
    conversation = [
        {"role": "user", "content": "Hello!"},
        {"role": "assistant", "content": "Hi there!"},
        {"role": "user", "content": "How are you?"},
        {"role": "assistant", "content": "I'm doing well!"},
    ]

    turns_windows = conversation_helpers.extract_turns_windows_from_conversation(
        conversation=conversation, window_size=2
    )

    assert len(turns_windows) == 2

    # Check that the first window has a list of dictionaries for the first turn
    # and the second window has full conversation
    assert len(turns_windows[0]) == 2
    assert turns_windows[0] == conversation[:2]

    assert len(turns_windows[1]) == 4
    assert turns_windows[1] == conversation


def test_extract_turns_windows_from_conversation__empty_conversation__raises_error():
    conversation = []

    with pytest.raises(ValueError):
        conversation_helpers.extract_turns_windows_from_conversation(
            conversation=conversation, window_size=2
        )


def test_extract_turns_windows_from_conversation__no_turns__raises_error():
    conversation = [
        {"role": "unknown", "content": "Hello!"},
        {"role": "someone", "content": "Hi there!"},
    ]

    with pytest.raises(ValueError):
        conversation_helpers.extract_turns_windows_from_conversation(
            conversation=conversation, window_size=2
        )
