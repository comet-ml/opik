def test_window_size(self, mock_get_turns):
    """Test that window_size parameter is correctly used."""
    conversation = [
        {"role": "user", "content": "Hello!"},
        {"role": "assistant", "content": "Hi there!"},
        {"role": "user", "content": "How are you?"},
        {"role": "assistant", "content": "I'm doing well!"},
    ]

    # Call score method
    self.metric.score(conversation=conversation)

    # Verify window_size was used
    mock_get_turns.assert_called_once_with(conversation, 2)
