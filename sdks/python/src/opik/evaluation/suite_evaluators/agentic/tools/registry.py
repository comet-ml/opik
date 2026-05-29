import json
import logging
from typing import Any, Dict, List

from . import executor
from .. import context

LOGGER = logging.getLogger(__name__)


class ToolRegistry:
    def __init__(self, tools: List[executor.ToolExecutor]) -> None:
        self._by_name: Dict[str, executor.ToolExecutor] = {}
        for tool in tools:
            if tool.name in self._by_name:
                raise ValueError(f"Duplicate tool name: {tool.name}")
            self._by_name[tool.name] = tool

    def specs(self) -> List[Dict[str, Any]]:
        return [tool.spec for tool in self._by_name.values()]

    def names(self) -> List[str]:
        return list(self._by_name.keys())

    def execute(
        self,
        name: str,
        arguments: str,
        ctx: context.TraceToolContext,
    ) -> str:
        tool = self._by_name.get(name)
        if tool is None:
            return json.dumps({"error": f"Unknown tool: {name}"})
        try:
            return tool.execute(arguments, ctx)
        except Exception as exc:
            LOGGER.warning(
                "Tool %s raised %s during execution; returning error to model",
                name,
                exc,
                exc_info=True,
            )
            return json.dumps({"error": f"Tool execution failed: {exc}"})
