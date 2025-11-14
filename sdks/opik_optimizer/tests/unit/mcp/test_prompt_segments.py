from opik_optimizer import ChatPrompt
from opik_optimizer.utils.prompt_segments import (
    apply_segment_updates,
    extract_prompt_segments,
    segment_ids_for_tools,
)


def test_extract_and_update_system_user_and_tool_segments() -> None:
    prompt = ChatPrompt(
        name="context7",
        system="You are a docs assistant.",
        user="Answer the question about {product}.",
        tools=[
            {
                "type": "function",
                "function": {
                    "name": "lookup",
                    "description": "Search the docs.",
                    "parameters": {
                        "type": "object",
                        "properties": {"query": {"type": "string"}},
                    },
                },
            }
        ],
    )

    segments = extract_prompt_segments(prompt)
    segment_map = {segment.segment_id: segment for segment in segments}

    assert segment_map["system"].content == "You are a docs assistant."
    assert segment_map["user"].content == "Answer the question about {product}."

    tool_ids = segment_ids_for_tools(segments)
    assert tool_ids == ["tool:lookup"]
    assert segment_map["tool:lookup"].content == "Search the docs."

    updates = {
        "system": "You are the authoritative docs agent.",
        "user": "Answer the developer question about {product}.",
        "tool:lookup": "Use when a developer asks for documentation snippets.",
    }

    updated = apply_segment_updates(prompt, updates)

    assert updated.system == updates["system"]
    assert updated.user == updates["user"]
    assert updated.tools is not None
    assert updated.tools[0]["function"]["description"] == updates["tool:lookup"]
    # original prompt remains unchanged
    assert prompt.system == "You are a docs assistant."
    assert prompt.tools is not None
    assert prompt.tools[0]["function"]["description"] == "Search the docs."


def test_extract_and_update_message_segments() -> None:
    prompt = ChatPrompt(
        name="message",
        messages=[
            {"role": "system", "content": "You are concise."},
            {"role": "user", "content": "Hi"},
        ],
    )

    segments = extract_prompt_segments(prompt)
    assert len(segments) == 2
    segment_map = {segment.segment_id: segment for segment in segments}
    assert segment_map["message:0"].content == "You are concise."
    assert segment_map["message:1"].content == "Hi"

    updates = {"message:1": "Hello there"}
    updated = apply_segment_updates(prompt, updates)

    assert updated.messages is not None
    assert updated.messages[1]["content"] == "Hello there"
    assert updated.messages[0]["content"] == "You are concise."
    # ensure original messages untouched
    assert prompt.messages is not None
    assert prompt.messages[1]["content"] == "Hi"


def test_apply_updates_ignores_unknown_segments() -> None:
    prompt = ChatPrompt(name="noop", user="Hello")
    updated = apply_segment_updates(prompt, {"message:99": "ignored"})
    assert updated.user == "Hello"
