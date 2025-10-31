import logging
from typing import Any, Dict, Optional, TYPE_CHECKING

from opik.api_objects import span
from opik.integrations.langchain import opik_tracer
from opik import exceptions

if TYPE_CHECKING:
    from langchain_core.callbacks.manager import AsyncCallbackManager

LOGGER = logging.getLogger(__name__)


def extract_current_langgraph_span_data(
    runnable_config: Dict[str, Any],
) -> Optional[span.SpanData]:
    """
    Extract current span data for the current LangGraph run.

    This helper utility extracts the current span data from a LangGraph configuration
    that contains an AsyncCallbackManager with OpikTracer handlers. It's specifically
    designed for async LangGraph agents that need to access the current span context
    for distributed tracing scenarios.

    Args:
        runnable_config: The config dictionary containing an AsyncCallbackManager
            in the "callbacks" key, automatically passed to LangGraph node functions.

    Returns:
        The current span data, or None if not found.

    Raises:
        OpikException: If the config structure is invalid or required components are missing.

    Example:
        ```python
        from opik.integrations.langchain import extract_current_langgraph_span_data

        @opik.track
        def f(x):
            return x * 2

        async def my_async_langgraph_node(state: dict, config: dict):
            # Extract current span for distributed tracing
            span_data = extract_current_langgraph_span_data(config)

            if span_data:
                # Propagate trace context
                result = f(
                    42,
                    opik_distributed_trace_headers=span_data.get_distributed_trace_headers()
                )

            return {"result": result}
        ```
    """
    try:
        # Get the AsyncCallbackManager from config
        callback_manager = runnable_config.get("callbacks")
        if callback_manager is None:
            raise exceptions.OpikException("No langchain callback manager found in runnable config")

        # Extract parent_run_id from the callback manager
        parent_run_id = getattr(callback_manager, "parent_run_id", None)
        if parent_run_id is None:
            raise exceptions.OpikException("parent_run_id not found in langchain callback manager")

        # Extract opik tracer from handlers
        handlers = getattr(callback_manager, "handlers", [])
        if not handlers:
            raise exceptions.OpikException("No handlers found in callback manager")

        # Find the OpikTracer in the handlers
        opik_tracer_instance: Optional[opik_tracer.OpikTracer] = None
        for handler in handlers:
            if hasattr(handler, "_span_data_map"):
                opik_tracer_instance = handler
                break

        if opik_tracer_instance is None:
            raise exceptions.OpikException("OpikTracer not found in handlers")

        # Get the span data associated with this run id
        span_data = opik_tracer_instance._span_data_map.get(parent_run_id)
        if span_data is None:
            raise exceptions.OpikException(f"Span data not found for run_id: {parent_run_id}")

        return span_data

    except Exception as exception:
        LOGGER.error(
            "Failed to extract Opik span data from runnable config: %s",
            exception,
            exc_info=exception,
        )
        return None