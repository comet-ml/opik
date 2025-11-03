import logging
from typing import Any, Dict, Optional

import opik.api_objects.span as span
import opik.integrations.langchain.opik_tracer as opik_tracer_module

LOGGER = logging.getLogger(__name__)


def extract_current_langgraph_span_data(
    runnable_config: Dict[str, Any],
) -> Optional[span.SpanData]:
    """
    Extract current span data for async LangGraph nodes.

    This helper function is specifically designed for async LangGraph execution using `ainvoke()`.
    Due to LangChain framework limitations in async scenarios, the execution context is not
    automatically shared between callbacks (like OpikTracer) and node code. This function
    extracts the current span data from the LangGraph config, allowing you to propagate
    trace context to @track-decorated functions via distributed headers.

    For synchronous execution using `invoke()`, this function is not needed as the context
    is automatically shared.

    Args:
        runnable_config: The config dictionary automatically passed to LangGraph node functions,
            containing an AsyncCallbackManager in the "callbacks" key.

    Returns:
        The current span data if found, None otherwise. Returns None if OpikTracer is not
        configured in the callbacks or if the span data cannot be extracted.

    Example:
        ```python
        from opik import track
        from opik.integrations.langchain import OpikTracer, extract_current_langgraph_span_data
        from langgraph.graph import StateGraph, START, END

        @track
        def process_data(value: int) -> int:
            return value * 2

        async def my_async_node(state, config):
            # Extract current span data from LangGraph config
            span_data = extract_current_langgraph_span_data(config)

            if span_data is not None:
                # Propagate trace context to tracked function
                result = process_data(
                    state["value"],
                    opik_distributed_trace_headers=span_data.get_distributed_trace_headers()
                )
                return {"value": result}

            return {"value": None}

        # Build and execute graph
        graph = StateGraph(dict)
        graph.add_node("processor", my_async_node)
        graph.add_edge(START, "processor")
        graph.add_edge("processor", END)

        app = graph.compile()
        opik_tracer = OpikTracer()

        # Asynchronous execution requires explicit trace context propagation
        result = await app.ainvoke({"value": 21}, config={"callbacks": [opik_tracer]})
        ```
    """
    try:
        callback_manager = runnable_config.get("callbacks")
        if callback_manager is None:
            LOGGER.warning(
                "Cannot extract span data: no callback manager found in LangGraph config. "
                "Ensure OpikTracer is passed in the config: config={'callbacks': [opik_tracer]}"
            )
            return None

        parent_run_id = getattr(callback_manager, "parent_run_id", None)
        handlers = getattr(callback_manager, "handlers", [])

        if parent_run_id is None or not handlers:
            LOGGER.warning(
                "Cannot extract span data: LangGraph callback manager is not properly initialized. "
                "This may occur if the node is called outside of a LangGraph execution context."
            )
            return None

        opik_tracer_instance = _find_opik_tracer(handlers)
        if opik_tracer_instance is None:
            LOGGER.warning(
                "Cannot extract span data: OpikTracer not found in LangGraph callbacks. "
                "Ensure OpikTracer is passed in the config: config={'callbacks': [opik_tracer]}"
            )
            return None

        span_data = opik_tracer_instance.get_current_span_data_for_run(parent_run_id)
        if span_data is None:
            LOGGER.warning(
                "Cannot extract span data: no span found for the current LangGraph node. "
                "This may occur if OpikTracer was not properly initialized or if the node "
                "is executing in an unexpected context."
            )
            return None

        return span_data

    except Exception as exception:
        LOGGER.warning(
            "Failed to extract span data from LangGraph config due to unexpected error",
            exc_info=exception,
        )
        return None


def _find_opik_tracer(
    handlers: list,
) -> Optional[opik_tracer_module.OpikTracer]:
    """
    Find OpikTracer instance in the list of handlers.

    Args:
        handlers: List of callback handlers.

    Returns:
        OpikTracer instance if found, None otherwise.
    """
    for handler in handlers:
        if isinstance(handler, opik_tracer_module.OpikTracer):
            return handler
    return None
