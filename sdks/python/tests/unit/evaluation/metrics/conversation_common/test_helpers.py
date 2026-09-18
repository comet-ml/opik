import datetime

import pytest

from opik.api_objects.conversation import conversation_factory
from opik.evaluation.metrics.conversation import helpers as conversation_helpers
from opik.rest_api import TracePublic
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


def test_extract_turns_windows_from_conversation__unanswered_turn_reaches_the_window():
    """What the sliding-window judges see must be the whole thread."""
    conversation = [
        {"role": "user", "content": "Hello!"},
        {"role": "user", "content": "Actually, what is the overdraft fee?"},
        {"role": "assistant", "content": "It is 5%."},
    ]

    turns_windows = conversation_helpers.extract_turns_windows_from_conversation(
        conversation=conversation, window_size=2
    )

    assert len(turns_windows) == 2
    assert turns_windows[-1] == conversation


def test_extract_turns_windows_from_conversation__unanswered_turn_from_traces_kept():
    """The gap the judges must survive comes from the trace loader, not only hand-built lists.

    ``create_conversation_from_traces`` adds an assistant message only when
    ``output_transform`` returns something, so one trace with an unusable output
    leaves two user messages next to each other.
    """
    start = datetime.datetime.now()
    traces = [
        TracePublic(
            id="019b0000-0000-7000-8000-000000000001",
            input={"x": "How do I reset my password?"},
            output={"output": "Go to settings."},
            start_time=start,
        ),
        TracePublic(
            id="019b0000-0000-7000-8000-000000000002",
            input={"x": "And when does it take effect?"},
            output={"result": "not the expected shape"},
            start_time=start + datetime.timedelta(seconds=1),
        ),
        TracePublic(
            id="019b0000-0000-7000-8000-000000000003",
            input={"x": "Thanks, that worked."},
            output={"output": "Glad to help."},
            start_time=start + datetime.timedelta(seconds=2),
        ),
    ]

    def input_transform(input_):
        return input_.get("x")

    def output_transform(output_):
        return output_.get("output")

    conversation = conversation_factory.create_conversation_from_traces(
        traces, input_transform, output_transform
    ).as_json_list()

    u1 = {"role": "user", "content": "How do I reset my password?"}
    a1 = {"role": "assistant", "content": "Go to settings."}
    u2 = {"role": "user", "content": "And when does it take effect?"}
    u3 = {"role": "user", "content": "Thanks, that worked."}
    a3 = {"role": "assistant", "content": "Glad to help."}

    assert [m["role"] for m in conversation] == [
        "user",
        "assistant",
        "user",
        "user",
        "assistant",
    ]

    # Each window is asserted against its own expected message list: window count plus
    # the final window alone would still pass if an unanswered turn were dropped from an
    # *intermediate* window, which is what the judges actually score against.
    expected_windows = [
        [u1, a1],
        [u1, a1, u2],
        [u1, a1, u2, u3, a3],
    ]
    turns_windows = conversation_helpers.extract_turns_windows_from_conversation(
        conversation=conversation, window_size=5
    )
    assert turns_windows == expected_windows

    # With a window of two turns the unanswered turn is the second window's only content
    # beyond the first pair, and the head of the third window.
    assert conversation_helpers.extract_turns_windows_from_conversation(
        conversation=conversation, window_size=2
    ) == [
        [u1, a1],
        [u1, a1, u2],
        [u2, u3, a3],
    ]


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
