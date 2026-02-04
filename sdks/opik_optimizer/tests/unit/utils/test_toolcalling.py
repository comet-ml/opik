from opik_optimizer import ChatPrompt
from opik_optimizer.utils.toolcalling.ops import toolcalling


def test_toolcalling__prepare_tool_optimization_all_tools() -> None:
    tools = [
        {
            "type": "function",
            "function": {
                "name": "search",
                "description": "search docs",
                "parameters": {"type": "object", "properties": {}},
            },
        },
        {
            "type": "function",
            "function": {
                "name": "summarize",
                "description": "summarize output",
                "parameters": {"type": "object", "properties": {}},
            },
        },
    ]
    prompt = ChatPrompt(system="sys", user="hi", tools=tools)
    resolved, tool_names = toolcalling.prepare_tool_optimization(prompt, True)
    assert resolved.tools is not None
    assert set(tool_names or []) == {"search", "summarize"}


def test_toolcalling__prepare_tool_optimization_subset() -> None:
    tools = [
        {
            "type": "function",
            "function": {
                "name": "search",
                "description": "search docs",
                "parameters": {"type": "object", "properties": {}},
            },
        },
        {
            "type": "function",
            "function": {
                "name": "summarize",
                "description": "summarize output",
                "parameters": {"type": "object", "properties": {}},
            },
        },
    ]
    prompt = ChatPrompt(system="sys", user="hi", tools=tools)
    resolved, tool_names = toolcalling.prepare_tool_optimization(
        prompt, {"summarize": True, "search": False}
    )
    assert resolved.tools is not None
    assert tool_names == ["summarize"]
