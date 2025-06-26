from opik.evaluation.metrics.conversation import conversation_turns_factory, helpers


def test_get_turns_in_sliding_window():
    """Test that the window_size parameter is correctly used."""
    conversation = [
        {"role": "user", "content": "Hello!"},
        {"role": "assistant", "content": "Hi there!"},
        {"role": "user", "content": "How are you?"},
        {"role": "assistant", "content": "I'm doing well!"},
    ]

    turns = conversation_turns_factory.build_conversation_turns(conversation)

    window_generator = helpers.get_turns_in_sliding_window(turns, window_size=2)

    # Check that the first window has 1 turn and the second window has 2
    expected_size = 1
    for window in window_generator:
        assert len(window) == expected_size
        expected_size += 1
