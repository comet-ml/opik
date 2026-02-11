from __future__ import annotations

from ...api_objects.chat_prompt import ChatPrompt
from .normalize.tool_factory import ToolCallingFactory


def extract_tool_descriptions(prompt: ChatPrompt) -> dict[str, str]:
    """Return function name -> description for resolved tool entries."""
    resolved_prompt = ToolCallingFactory().resolve_prompt(prompt)
    descriptions: dict[str, str] = {}
    for tool in resolved_prompt.tools or []:
        function = tool.get("function", {})
        name = function.get("name")
        if isinstance(name, str):
            descriptions[name] = str(function.get("description", ""))
    return descriptions
