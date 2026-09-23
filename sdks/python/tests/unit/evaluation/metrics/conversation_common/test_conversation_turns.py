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


def test_build_conversation_turns__messages_with_context__context_preserved():
    conversation = [
        {"role": "user", "content": "What is the overdraft fee?"},
        {
            "role": "assistant",
            "content": "It is 5%.",
            "context": ["The overdraft fee is 5%."],
        },
    ]
    turns = conversation_turns.build_conversation_turns(conversation)
    assert len(turns) == 1
    assert turns[0].output == conversation[1]


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


def test_build_conversation_turns__unanswered_user_message_mid_conversation_kept():
    conversation = [
        {"role": "user", "content": "What is the overdraft fee?"},
        {"role": "user", "content": "Tool output: the policy says 5%."},
        {"role": "assistant", "content": "It is 5%."},
    ]
    turns = conversation_turns.build_conversation_turns(conversation)

    assert [m["content"] for turn in turns for m in turn.as_list()] == [
        "What is the overdraft fee?",
        "Tool output: the policy says 5%.",
        "It is 5%.",
    ]
    assert turns[0].output is None
    assert turns[1].input == conversation[1]
    assert turns[1].output == conversation[2]


def test_build_conversation_turns__unanswered_message_between_turns_keeps_its_turn():
    conversation = [
        {"role": "user", "content": "U1"},
        {"role": "assistant", "content": "A1"},
        {"role": "user", "content": "U2"},
        {"role": "user", "content": "U3"},
        {"role": "assistant", "content": "A3"},
    ]
    turns = conversation_turns.build_conversation_turns(conversation)

    # U2 never got an answer, so it is a turn of its own - like the tail case -
    # rather than something the following question replaces.
    assert [(t.input["content"], (t.output or {}).get("content")) for t in turns] == [
        ("U1", "A1"),
        ("U2", None),
        ("U3", "A3"),
    ]


def test_build_conversation_turns__alternating_conversation_unchanged():
    conversation = [
        {"role": "user", "content": "U1"},
        {"role": "assistant", "content": "A1"},
        {"role": "user", "content": "U2"},
        {"role": "assistant", "content": "A2"},
    ]
    turns = conversation_turns.build_conversation_turns(conversation)
    assert [(t.input["content"], t.output["content"]) for t in turns] == [
        ("U1", "A1"),
        ("U2", "A2"),
    ]
