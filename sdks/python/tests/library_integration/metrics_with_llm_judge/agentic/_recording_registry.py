"""Tool-call recorder for agentic-judge integration tests.

The agentic loop runs against a real LLM; from the verdict alone we
can't tell whether the model recovered the answer from the inline
overview or actually exercised the `read` / `scan` / `search` drill-in
tools. `RecordingToolRegistry` wraps a base `ToolRegistry` and appends
each `execute(name, arguments, ctx)` call to a public list so tests
can assert tool use directly.

Why not just count tokens or check overview state: the only
authoritative signal is "did the model invoke a tool?", and that
question is answered at the registry seam. Anything upstream (the
loop, the model wrapper) is implementation detail that could change
without affecting the test's intent.
"""

from typing import List, Tuple

from opik.evaluation.suite_evaluators.agentic import context
from opik.evaluation.suite_evaluators.agentic.tools import registry as tool_registry


class RecordingToolRegistry(tool_registry.ToolRegistry):
    """A `ToolRegistry` proxy that appends every `execute` call to a list.

    Reuses the wrapped registry's `_by_name` map directly — we don't
    want to re-validate the (already-validated) tool set, and we want
    the same executor instances so any state they hold stays coherent.
    """

    def __init__(self, base: tool_registry.ToolRegistry) -> None:
        # Skip `super().__init__` — it would require re-passing the
        # tool list and re-running the duplicate-name check the base
        # registry already performed.
        self._by_name = dict(base._by_name)
        self.calls: List[Tuple[str, str]] = []

    def execute(
        self,
        name: str,
        arguments: str,
        ctx: context.TraceToolContext,
    ) -> str:
        self.calls.append((name, arguments))
        return super().execute(name, arguments, ctx)

    def tool_names_called(self) -> List[str]:
        """Convenience: just the tool names, in invocation order."""
        return [name for name, _ in self.calls]
