from typing import Any

import pytest

from opik_optimizer import ChatPrompt
from opik_optimizer.utils.toolcalling.ops import toolcalling


def _make_tools(n: int) -> list[dict[str, Any]]:
    tools = []
    for idx in range(n):
        tools.append(
            {
                "type": "function",
                "function": {
                    "name": f"tool_{idx}",
                    "description": f"Tool {idx}",
                    "parameters": {"type": "object", "properties": {}},
                },
            }
        )
    return tools


def test_prepare_tool_optimization_enforces_limit(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    monkeypatch.delenv("DEFAULT_TOOL_CALL_MAX_TOOLS_TO_OPTIMIZE", raising=False)
    prompt = ChatPrompt(system="sys", user="hi", tools=_make_tools(4))
    with pytest.raises(ValueError, match="supports at most"):
        toolcalling.prepare_tool_optimization(prompt, True)


def test_prepare_tool_optimization_allows_limit(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    prompt = ChatPrompt(system="sys", user="hi", tools=_make_tools(3))
    resolved, tool_names = toolcalling.prepare_tool_optimization(prompt, True)
    assert resolved.tools is not None
    assert tool_names is not None
    assert len(tool_names) == 3
