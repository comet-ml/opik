"""Opik ↔ Atomic Agents core tracer.

Phase-1 implementation – minimal yet functional.
It starts an Opik Trace (and root Span) when requested and finalises it on
completion or exception.  Tool-level child span logic will be added in later
commits.
"""

from __future__ import annotations

from typing import Any, Dict, List, Optional, Union, TYPE_CHECKING

from opik import context_storage  # type: ignore
from opik.api_objects import opik_client, trace
from opik.decorator import error_info_collector  # type: ignore
from opik.opik_context import trace_context  # type: ignore

if TYPE_CHECKING:  # pragma: no cover
    from opik.types import ErrorInfoDict  # pylint: disable=ungrouped-imports


class OpikAtomicAgentsTracer:  # pylint: disable=too-few-public-methods
    """Bridge Atomic Agents executions with Opik traces.

    Notes
    -----
    *   Only the *root* trace/span is handled in this commit – no child spans yet.
    *   Designed to be used as a context-manager **or** via explicit :py:meth:`start`
        / :py:meth:`end` calls (decorators will leverage that later).
    """

    _CREATED_FROM = "atomic_agents"

    # ---------------------------------------------------------------------
    # Construction helpers
    # ---------------------------------------------------------------------
    def __init__(
        self,
        project_name: Optional[str] = None,
        *,
        tags: Optional[List[str]] = None,
        metadata: Optional[Dict[str, Any]] = None,
    ) -> None:  # noqa: D401  (simple init)
        self.project_name = project_name
        self.tags = tags or []
        self.metadata: Dict[str, Any] = metadata.copy() if metadata else {}
        self.metadata.setdefault("created_from", self._CREATED_FROM)

        self._opik_client = opik_client.get_client_cached()
        self._context_storage = context_storage.get_current_context_instance()

        self._trace_data: Optional[trace.TraceData] = None

    # ------------------------------------------------------------------
    # Public API – explicit calls
    # ------------------------------------------------------------------
    def start(
        self,
        *,
        name: str,
        input: Optional[Dict[str, Any]] = None,
        agent_instance: Optional[Any] = None,
    ) -> None:
        """Begin a new Opik trace.

        Parameters
        ----------
        name
            Human-readable name for the trace (usually the agent class / task).
        input
            Input payload forwarded to the agent.  Saved to trace `input` field.
        """
        if self._trace_data is not None:  # pragma: no cover – misuse safeguard
            raise RuntimeError("Tracer already started – call .end() first")

        # Clone metadata so we don't mutate the original dict passed in __init__
        metadata = dict(self.metadata)

        # Attempt to capture agent input/output schemas for richer inspection
        if agent_instance is not None:
            self._attach_agent_schemas(agent_instance, metadata)

        self._trace_data = trace.TraceData(
            name=name,
            input=input,
            metadata=metadata,
            tags=self.tags,
            project_name=self.project_name,
        )

        # Push into context so nested spans (added later) have a parent.
        self._context_storage.set_trace_data(self._trace_data)

        if self._opik_client.config.log_start_trace_span:  # type: ignore[attr-defined]
            self._opik_client.trace(**self._trace_data.as_start_parameters)

    def end(
        self,
        *,
        output: Optional[Dict[str, Any]] = None,
        error_info: "Optional[ErrorInfoDict]" = None,
    ) -> None:
        """Finalize the trace and flush to Opik backend."""
        if self._trace_data is None:  # pragma: no cover – misuse safeguard
            return  # Nothing to end

        # Update data & timestamps
        self._trace_data.init_end_time().update(output=output, error_info=error_info)
        self._opik_client.trace(**self._trace_data.as_parameters)

        # Pop from context if still top
        try:
            self._context_storage.pop_trace_data(ensure_id=self._trace_data.id)  # type: ignore[attr-defined]
        except Exception:  # pragma: no cover – context maybe already cleared
            self._context_storage.set_trace_data(None)

        # Clear local reference so tracer can be reused if desired
        self._trace_data = None

    # ------------------------------------------------------------------
    # Context-manager helpers
    # ------------------------------------------------------------------
    def __enter__(self) -> "OpikAtomicAgentsTracer":  # noqa: D401
        # Default name – can be overridden via explicit .start()
        self.start(name="atomic_agents_run")
        return self

    def __exit__(
        self,
        exc_type,  # pylint: disable=unused-argument
        exc_val: Optional[BaseException],  # noqa: D401
        exc_tb,  # pylint: disable=unused-argument
    ) -> bool:  # True → suppress exception? we propagate, so False
        error_info: "Optional[ErrorInfoDict]" = None
        if exc_val is not None:
            # Collect structured error details.
            error_info = error_info_collector.collect(exc_val)  # type: ignore[arg-type]

        self.end(error_info=error_info)
        # Do NOT suppress exceptions – return False
        return False

    # ------------------------------------------------------------------
    # String helpers
    # ------------------------------------------------------------------
    def __repr__(self) -> str:  # noqa: D401
        project = self.project_name or "<default>"
        return f"<OpikAtomicAgentsTracer project={project}>" 

    # ------------------------------------------------------------------
    # Private helpers
    # ------------------------------------------------------------------

    def _extract_json_schema(self, model_cls: Any) -> Optional[Dict[str, Any]]:  # noqa: ANN401
        """Return JSON schema for a Pydantic model class, if possible."""
        try:
            from pydantic import BaseModel  # type: ignore

            if isinstance(model_cls, type) and issubclass(model_cls, BaseModel):
                return model_cls.model_json_schema()  # type: ignore[attr-defined]
        except Exception:  # pragma: no cover – optional dependency or failure
            pass
        return None

    def _attach_agent_schemas(self, agent_instance: Any, meta: Dict[str, Any]) -> None:  # noqa: ANN401
        """Inject input/output schema JSON into metadata if available."""

        input_schema_json: Optional[Dict[str, Any]] = None
        output_schema_json: Optional[Dict[str, Any]] = None

        # Atomic Agents convention: attributes on class
        if hasattr(agent_instance, "input_schema"):
            input_schema_json = self._extract_json_schema(agent_instance.input_schema)  # type: ignore[arg-type]

        if hasattr(agent_instance, "output_schema"):
            output_schema_json = self._extract_json_schema(agent_instance.output_schema)  # type: ignore[arg-type]

        if input_schema_json is not None:
            meta["atomic_input_schema"] = input_schema_json

        if output_schema_json is not None:
            meta["atomic_output_schema"] = output_schema_json 