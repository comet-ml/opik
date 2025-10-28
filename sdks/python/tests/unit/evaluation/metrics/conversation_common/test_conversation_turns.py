from opik.evaluation.metrics.conversation import (
    conversation_turns_factory as conversation_turns,
)


def test_build_conversation_turns__happy_path():
    conversation = [
        {"role": "user", "content": "Hi!"},
        {"role": "assistant", "content": "Hello! How can I help you today?"},
        {"role": "user", "content": "How are you?"},
        {"role": "assistant", "content": "I'm doing well!"},
    ]
    turns = conversation_turns.build_conversation_turns(conversation)
    assert len(turns) == 2
    assert turns[0].input == conversation[0]
    assert turns[0].output == conversation[1]
    assert turns[1].input == conversation[2]
    assert turns[1].output == conversation[3]


def test_build_conversation_turns__last_user_message_included():
    conversation = [
        {"role": "user", "content": "Hi!"},
        {"role": "assistant", "content": "Hello! How can I help you today?"},
        {"role": "user", "content": "How are you?"},
        {"role": "assistant", "content": "I'm doing well!"},
        {"role": "user", "content": "I'm doing well too!"},
    ]
    turns = conversation_turns.build_conversation_turns(conversation)
    assert len(turns) == 3
    assert turns[0].input == conversation[0]
    assert turns[0].output == conversation[1]
    assert turns[1].input == conversation[2]
    assert turns[1].output == conversation[3]
    assert turns[2].input == conversation[4]
    assert turns[2].output is None
