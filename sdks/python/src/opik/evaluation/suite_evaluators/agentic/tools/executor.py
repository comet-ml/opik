import abc
from typing import Any, ClassVar, Dict

from ..context import TraceToolContext


class ToolExecutor(abc.ABC):
    """Tool callable by the agentic LLM judge.

    Implementations must never raise out of `execute`: errors are returned
    as JSON strings (`{"error": "..."}`) so the tool-call loop can continue
    and let the model retry.
    """

    name: ClassVar[str]
    spec: ClassVar[Dict[str, Any]]

    @abc.abstractmethod
    def execute(self, arguments: str, ctx: TraceToolContext) -> str: ...
