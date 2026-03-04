from opik_optimizer import ChatPrompt
from opik_optimizer.utils import prompt_segments


def test_prompt_segments__update_tool_description() -> None:
    tools = [
        {
            "type": "function",
            "function": {
                "name": "search",
                "description": "old description",
                "parameters": {"type": "object", "properties": {}},
            },
        }
    ]
    prompt = ChatPrompt(system="sys", user="hello", tools=tools)

    segments = prompt_segments.extract_prompt_segments(prompt)
    assert any(segment.segment_id == "tool:search" for segment in segments)

    updated = prompt_segments.apply_segment_updates(
        prompt, {"tool:search": "new description"}
    )
    assert updated.tools is not None
    assert updated.tools[0]["function"]["description"] == "new description"
    assert prompt.tools is not None
    assert prompt.tools[0]["function"]["description"] == "old description"


def test_prompt_segments__update_multiple_segments() -> None:
    tools = [
        {
            "type": "function",
            "function": {
                "name": "lookup",
                "description": "search the docs",
                "parameters": {
                    "type": "object",
                    "properties": {"query": {"type": "string"}},
                    "required": ["query"],
                },
            },
        }
    ]
    prompt = ChatPrompt(
        messages=[
            {"role": "system", "content": "Base system"},
            {"role": "user", "content": "Ask about {topic}"},
        ],
        tools=tools,
    )

    segments = prompt_segments.extract_prompt_segments(prompt)
    assert {segment.segment_id for segment in segments} >= {
        "message:0",
        "message:1",
        "tool:lookup",
    }

    updated = prompt_segments.apply_segment_updates(
        prompt,
        {
            "message:0": "Updated system guidance",
            "message:1": "Ask about {topic} and cite sources",
            "tool:lookup": "Search the docs and return citations",
        },
    )

    assert updated.messages is not None
    assert updated.messages[0]["content"] == "Updated system guidance"
    assert updated.messages[1]["content"] == "Ask about {topic} and cite sources"
    assert updated.tools is not None
    assert (
        updated.tools[0]["function"]["description"]
        == "Search the docs and return citations"
    )
    assert updated.tools[0]["function"]["parameters"]["required"] == ["query"]
    assert prompt.messages is not None
    assert prompt.messages[0]["content"] == "Base system"
    assert prompt.messages[1]["content"] == "Ask about {topic}"


def test_prompt_segments__preserves_untouched_messages() -> None:
    prompt = ChatPrompt(
        messages=[
            {"role": "system", "content": "System stays"},
            {"role": "user", "content": "User stays"},
            {"role": "assistant", "content": "Assistant stays"},
        ]
    )

    updated = prompt_segments.apply_segment_updates(
        prompt, {"message:1": "User updated"}
    )

    assert updated.messages is not None
    assert updated.messages[0]["content"] == "System stays"
    assert updated.messages[1]["content"] == "User updated"
    assert updated.messages[2]["content"] == "Assistant stays"

    assert prompt.messages is not None
    assert prompt.messages[0]["content"] == "System stays"
    assert prompt.messages[1]["content"] == "User stays"
    assert prompt.messages[2]["content"] == "Assistant stays"
