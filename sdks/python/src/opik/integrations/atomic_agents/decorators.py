"""Automatic instrumentation helpers for Atomic Agents.

This module exposes :pyfunc:`track_atomic_agents` – a convenience helper that
monkey-patches Atomic Agents' ``BaseAgent.run`` so every agent execution is
logged to Opik without any code changes in the user project.

Only root traces are captured at this stage.  Child tool spans and schema
capture will be implemented in later commits.
"""

from __future__ import annotations

from typing import Any, Callable, Dict, List, Optional

from opik.integrations.atomic_agents.opik_tracer import OpikAtomicAgentsTracer
from opik.decorator import error_info_collector  # type: ignore

try:
    from atomic_agents.agents.base_agent import BaseAgent  # type: ignore
except ModuleNotFoundError:  # pragma: no cover – dependency optional
    BaseAgent = None  # type: ignore

# --------------------------------------------------------------------------------------
# Public helper
# --------------------------------------------------------------------------------------
__IS_TRACKING_ENABLED = False


def track_atomic_agents(
    *,
    project_name: Optional[str] = None,
    tags: Optional[List[str]] = None,
    metadata: Optional[Dict[str, Any]] = None,
) -> None:  # noqa: D401
    """Enable Opik tracing for *all* Atomic Agents by patching ``BaseAgent.run``.

    Example
    -------
    >>> from opik.integrations.atomic_agents import track_atomic_agents
    >>> track_atomic_agents(project_name="my-app")
    >>> # all subsequent agent.run(...) calls will be traced
    """

    global __IS_TRACKING_ENABLED  # pylint: disable=global-statement
    if __IS_TRACKING_ENABLED:
        return

    if BaseAgent is None:  # pragma: no cover – library not installed
        return

    original_run: Callable[[Any, Any], Any] = BaseAgent.run  # type: ignore[attr-defined]

    def _serialize_input(arg):  # noqa: D401
        if arg is None:
            return None
        if isinstance(arg, dict):
            return arg
        if hasattr(arg, "dict") and callable(arg.dict):  # pydantic models
            try:
                return arg.dict()  # type: ignore[attr-defined]
            except Exception:  # pragma: no cover
                pass
        return {"input": str(arg)}

    def _serialize_output(result):  # noqa: D401
        if result is None:
            return None
        if isinstance(result, dict):
            return result
        if hasattr(result, "dict") and callable(result.dict):
            try:
                return result.dict()  # type: ignore[attr-defined]
            except Exception:  # pragma: no cover
                pass
        return {"output": str(result)}

    def _wrapped_run(self, *args, **kwargs):  # type: ignore[override]
        tracer = OpikAtomicAgentsTracer(
            project_name=project_name,
            tags=tags,
            metadata=metadata,
        )

        # Determine agent name + input payload (best effort)
        input_payload = None
        if args:
            input_payload = _serialize_input(args[0])
        elif kwargs:
            # Atomic Agents typically uses single positional; fall back to kwargs.
            input_payload = {k: _serialize_input(v) for k, v in kwargs.items()}

        tracer.start(
            name=self.__class__.__name__,
            input=input_payload,
            agent_instance=self,
        )  # type: ignore[attr-defined]

        try:
            result = original_run(self, *args, **kwargs)
            tracer.end(output=_serialize_output(result))
            return result
        except Exception as exc:  # noqa: BLE001
            tracer.end(error_info=error_info_collector.collect(exc))
            raise

    BaseAgent.run = _wrapped_run  # type: ignore[assignment]

    __IS_TRACKING_ENABLED = True


__all__: list[str] = [
    "track_atomic_agents",
] 