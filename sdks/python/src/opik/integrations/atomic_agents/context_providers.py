"""Context providers bridging Opik traces to Atomic Agents prompts."""

from __future__ import annotations

from typing import Any, Dict, Optional

try:
    from atomic_agents.lib.components.system_prompt_generator import \
        SystemPromptContextProviderBase  # type: ignore
except ModuleNotFoundError:  # pragma: no cover
    SystemPromptContextProviderBase = object  # type: ignore


def _format_trace_info(trace_id: str, project_name: Optional[str]) -> str:  # noqa: D401
    project_line = f"project:  {project_name}" if project_name else ""
    return f"[Opik Trace Info]\ntrace_id: {trace_id}\n{project_line}"


class OpikContextProvider(SystemPromptContextProviderBase):  # type: ignore[misc]
    """Inject minimal Opik trace context into Atomic Agents system prompt."""

    def __init__(
        self,
        *,
        project_name: Optional[str] = None,
        include_metadata: bool = False,
        metadata: Optional[Dict[str, Any]] = None,
        trace_id: Optional[str] = None,
    ) -> None:
        super().__init__(title="opik_context")  # type: ignore[call-arg]
        self._project_name = project_name
        self._include_metadata = include_metadata
        self._metadata = metadata or {}
        self._trace_id: Optional[str] = trace_id

    # Consumers may call this once they have the trace id.
    def set_trace_id(self, trace_id: str) -> None:  # noqa: D401
        self._trace_id = trace_id

    # ------------------------------------------------------------------
    def get_info(self) -> str:  # type: ignore[override]
        if self._trace_id is None:
            return ""

        info = _format_trace_info(self._trace_id, self._project_name)
        if self._include_metadata and self._metadata:
            info += "\n" + "\n".join(f"{k}: {v}" for k, v in self._metadata.items())
        return info


__all__: list[str] = [
    "OpikContextProvider",
]
