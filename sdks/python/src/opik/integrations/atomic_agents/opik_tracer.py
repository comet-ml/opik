"""Opik ↔ Atomic Agents core tracer.

Phase-1 implementation – minimal yet functional.
It starts an Opik Trace (and root Span) when requested and finalises it on
completion or exception.  Tool-level child span logic will be added in later
commits.
"""

from __future__ import annotations

from typing import TYPE_CHECKING, Any, Dict, List, Optional

from opik.api_objects import opik_client, trace
from opik.decorator import error_info_collector

from opik import context_storage

if TYPE_CHECKING:
    from opik.types import ErrorInfoDict


class OpikAtomicAgentsTracer:
    """Trace Atomic Agents workflows as standard Opik traces/spans."""

    def __init__(
        self,
        project_name: Optional[str] = None,
        *,
        tags: Optional[List[str]] = None,
        metadata: Optional[Dict[str, Any]] = None,
    ) -> None:
        self.project_name = project_name
        self.tags = tags or []
        self.metadata: Dict[str, Any] = metadata.copy() if metadata else {}
        self.metadata.setdefault("created_from", "atomic_agents")

        self._opik_client = opik_client.get_client_cached()
        self._context_storage = context_storage.get_current_context_instance()
        self._trace_data: Optional[trace.TraceData] = None

    # Public API ---------------------------------------------------------
    def start(
        self,
        *,
        name: str,
        input: Optional[Dict[str, Any]] = None,
        agent_instance: Optional[Any] = None,
    ) -> None:
        if self._trace_data is not None:
            raise RuntimeError("Tracer already started – call .end() first")

        meta = dict(self.metadata)
        if agent_instance is not None:
            self._attach_agent_schemas(agent_instance, meta)

        self._trace_data = trace.TraceData(
            name=name,
            input=input,
            metadata=meta,
            tags=self.tags,
            project_name=self.project_name,
        )

        self._context_storage.set_trace_data(self._trace_data)

        if self._opik_client.config.log_start_trace_span:
            self._opik_client.trace(**self._trace_data.as_start_parameters)

    def end(
        self,
        *,
        output: Optional[Dict[str, Any]] = None,
        error_info: Optional["ErrorInfoDict"] = None,
    ) -> None:
        if self._trace_data is None:
            return

        self._trace_data.init_end_time().update(output=output, error_info=error_info)
        self._opik_client.trace(**self._trace_data.as_parameters)

        try:
            self._context_storage.pop_trace_data(ensure_id=self._trace_data.id)
        except Exception:
            self._context_storage.set_trace_data(None)

        self._trace_data = None

    # Context manager ----------------------------------------------------
    def __enter__(self) -> "OpikAtomicAgentsTracer":
        self.start(name="atomic_agents_run")
        return self

    def __exit__(self, exc_type, exc_val: Optional[BaseException], exc_tb) -> bool:  # type: ignore
        error = error_info_collector.collect(exc_val) if exc_val else None
        self.end(error_info=error)
        return False

    # --------------------------------------------------------------------
    def _extract_json_schema(self, model_cls: Any) -> Optional[Dict[str, Any]]:
        try:
            from pydantic import BaseModel  # type: ignore

            if isinstance(model_cls, type) and issubclass(model_cls, BaseModel):
                return model_cls.model_json_schema()  # type: ignore[attr-defined]
        except Exception:
            return None
        return None

    def _attach_agent_schemas(self, agent_instance: Any, meta: Dict[str, Any]) -> None:
        if hasattr(agent_instance, "input_schema"):
            schema = self._extract_json_schema(agent_instance.input_schema)
            if schema:
                meta["atomic_input_schema"] = schema

        if hasattr(agent_instance, "output_schema"):
            schema = self._extract_json_schema(agent_instance.output_schema)
            if schema:
                meta["atomic_output_schema"] = schema
