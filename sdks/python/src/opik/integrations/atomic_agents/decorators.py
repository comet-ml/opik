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
from opik.api_objects import opik_client

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

    # ------------------------------------------------------------------
    # Patch tools so that individual tool executions create `tool` spans
    # ------------------------------------------------------------------
    try:
        from atomic_agents.tools.base_tool import BaseTool  # type: ignore

        if not getattr(BaseTool, "__opik_opik_patched__", False):  # noqa: getattr-with-default
            original_tool_run = BaseTool.run  # type: ignore[attr-defined]

            def _wrapped_tool_run(self, *args, **kwargs):  # noqa: D401, ANN001
                from opik.decorator import arguments_helpers, span_creation_handler  # inline import to avoid cycles
                from opik import context_storage

                # Build StartSpanParameters for tool span
                input_payload = args[0] if args else kwargs or None

                start_params = arguments_helpers.StartSpanParameters(
                    name=getattr(self, "name", self.__class__.__name__),
                    type="tool",
                    input=input_payload if isinstance(input_payload, dict) else {"input": str(input_payload)},
                )

                _, span_data = span_creation_handler.create_span_respecting_context(
                    start_span_arguments=start_params,
                    distributed_trace_headers=None,
                    opik_context_storage=context_storage.get_current_context_instance(),
                )

                client = opik_client.get_client_cached()

                if client.config.log_start_trace_span:
                    client.span(**span_data.as_start_parameters)

                context_storage.add_span_data(span_data)

                try:
                    result = original_tool_run(self, *args, **kwargs)
                    span_data.init_end_time().update(output=result if isinstance(result, dict) else {"output": result})
                    client.span(**span_data.as_parameters)
                    return result
                except Exception as exc:  # noqa: BLE001
                    from opik.decorator import error_info_collector

                    span_data.init_end_time().update(error_info=error_info_collector.collect(exc))
                    client.span(**span_data.as_parameters)
                    raise
                finally:
                    context_storage.pop_span_data(ensure_id=span_data.id)

            BaseTool.run = _wrapped_tool_run  # type: ignore[assignment]
            BaseTool.__opik_opik_patched__ = True  # type: ignore[attr-defined]
    except ModuleNotFoundError:  # pragma: no cover
        pass

    __IS_TRACKING_ENABLED = True


__all__: list[str] = [
    "track_atomic_agents",
] 