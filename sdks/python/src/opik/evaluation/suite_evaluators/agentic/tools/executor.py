from typing import Any, Dict, Protocol, TYPE_CHECKING

if TYPE_CHECKING:
    from ..context import TraceToolContext


class ToolExecutor(Protocol):
    """Tool callable by the agentic LLM judge.

    Implementations must never raise out of `execute`: errors are returned
    as JSON strings (`{"error": "..."}`) so the tool-call loop can continue
    and let the model retry.
    """

    name: str
    spec: Dict[str, Any]

    def execute(self, arguments: str, ctx: "TraceToolContext") -> str: ...
