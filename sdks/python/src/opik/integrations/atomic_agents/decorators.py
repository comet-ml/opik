"""Atomic Agents auto-tracking decorators."""

from __future__ import annotations

from typing import Any, Dict, List, Optional

from opik.api_objects import opik_client
from opik.integrations.atomic_agents.opik_tracer import OpikAtomicAgentsTracer
from opik.decorator import (
    arguments_helpers,
    span_creation_handler,
    error_info_collector,
)

from opik import context_storage

__IS_TRACKING_ENABLED = False


def track_atomic_agents(
    *,
    project_name: Optional[str] = None,
    tags: Optional[List[str]] = None,
    metadata: Optional[Dict[str, Any]] = None,
) -> None:
    """Patch Atomic Agents so every agent and tool execution is traced."""

    try:
        from atomic_agents.agents.base_agent import BaseAgent
    except ModuleNotFoundError:
        return

    global __IS_TRACKING_ENABLED
    if __IS_TRACKING_ENABLED:
        return

    if getattr(BaseAgent, "__opik_patched__", False):
        return

    original_run = BaseAgent.run  # type: ignore[attr-defined]

    def _agent_run(self: Any, *args: Any, **kwargs: Any) -> Any:  # type: ignore[no-self-use]
        tracer = OpikAtomicAgentsTracer(
            project_name=project_name,
            tags=tags,
            metadata=metadata,
        )

        payload: Any = args[0] if args else kwargs or None
        input_dict = payload if isinstance(payload, dict) else {"input": str(payload)}

        tracer.start(
            name=self.__class__.__name__, input=input_dict, agent_instance=self
        )

        try:
            result = original_run(self, *args, **kwargs)
            tracer.end(
                output=result if isinstance(result, dict) else {"output": result}
            )
            return result
        except Exception as exc:
            tracer.end(error_info=error_info_collector.collect(exc))
            raise

    BaseAgent.run = _agent_run  # type: ignore[assignment]
    BaseAgent.__opik_patched__ = True  # type: ignore[attr-defined]
    _patch_atomic_tools()
    _patch_atomic_llms()
    __IS_TRACKING_ENABLED = True


__all__: List[str] = [
    "track_atomic_agents",
]


def _patch_atomic_tools() -> None:
    try:
        from atomic_agents.lib.base.base_tool import BaseTool
    except ModuleNotFoundError:
        return

    if getattr(BaseTool, "__opik_patched__", False):
        return

    original_run = BaseTool.run  # type: ignore[attr-defined]

    def _tool_run(self: Any, *args: Any, **kwargs: Any) -> Any:  # type: ignore[no-self-use]
        payload: Any = args[0] if args else kwargs or None
        input_dict = payload if isinstance(payload, dict) else {"input": str(payload)}

        start_params = arguments_helpers.StartSpanParameters(
            name=getattr(self, "name", self.__class__.__name__),
            type="tool",
            input=input_dict,
        )

        current_context = context_storage.get_current_context_instance()

        _, span_data = span_creation_handler.create_span_respecting_context(
            start_span_arguments=start_params,
            distributed_trace_headers=None,
            opik_context_storage=current_context,
        )

        span_data.parent_span_id = None

        client = opik_client.get_client_cached()
        if client.config.log_start_trace_span:
            client.span(**span_data.as_start_parameters)

        context_storage.add_span_data(span_data)

        try:
            result = original_run(self, *args, **kwargs)
            span_data.init_end_time().update(
                output=result if isinstance(result, dict) else {"output": result}
            )
            client.span(**span_data.as_parameters)
            return result
        except Exception as exc:
            span_data.init_end_time().update(
                error_info=error_info_collector.collect(exc)
            )
            client.span(**span_data.as_parameters)
            raise
        finally:
            context_storage.pop_span_data(ensure_id=span_data.id)

    BaseTool.run = _tool_run  # type: ignore[assignment]
    BaseTool.__opik_patched__ = True  # type: ignore[attr-defined]


def _patch_atomic_llms() -> None:
    try:
        from atomic_agents.agents.base_agent import BaseAgent
    except ModuleNotFoundError:
        return

    if getattr(BaseAgent, "__opik_patched_llm__", False):
        return

    original_get_response = BaseAgent.get_response

    from opik.decorator import arguments_helpers, span_creation_handler

    from opik import context_storage

    def _llm_get_response(self: Any, *args: Any, **kwargs: Any) -> Any:
        messages = kwargs.get("messages") or args[0] if args else []

        model_name = "unknown_model"
        if hasattr(self, "config") and hasattr(self.config, "model"):
            model_name = self.config.model
        elif hasattr(self, "model"):
            model_name = self.model
        elif hasattr(self, "_config") and hasattr(self._config, "model"):
            model_name = self._config.model

        start_params = arguments_helpers.StartSpanParameters(
            name=model_name,
            type="llm",
            input={"messages": messages},
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
            result = original_get_response(self, *args, **kwargs)

            output = {}
            if hasattr(result, "dict"):
                output = result.dict()
            elif isinstance(result, dict):
                output = result
            elif isinstance(result, str):
                output = {"content": result}

            span_data.init_end_time().update(output=output)
            client.span(**span_data.as_parameters)
            return result
        except Exception as exc:
            span_data.init_end_time().update(
                error_info=error_info_collector.collect(exc)
            )
            client.span(**span_data.as_parameters)
            raise
        finally:
            context_storage.pop_span_data(ensure_id=span_data.id)

    BaseAgent.get_response = _llm_get_response
    BaseAgent.__opik_patched_llm__ = True
