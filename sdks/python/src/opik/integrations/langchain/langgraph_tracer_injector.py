import logging
from typing import Any, Dict, List, TypeVar

from langchain_core.runnables import base as runnables_base

from . import opik_tracer as opik_tracer_module

LOGGER = logging.getLogger(__name__)

CompiledGraphType = TypeVar("CompiledGraphType", bound=runnables_base.Runnable)


def track_langgraph(
    graph: CompiledGraphType,
    opik_tracer: opik_tracer_module.OpikTracer,
) -> CompiledGraphType:
    """
    Adds Opik tracking to a compiled LangGraph graph by injecting OpikTracer into its default config.

    After calling this function, all subsequent invocations of the graph will automatically
    be tracked without needing to pass the OpikTracer in the config parameter.

    The function will automatically extract the graph structure visualization from the compiled
    graph if it wasn't already provided when creating the OpikTracer. This visualization will
    be included in the trace metadata in the Opik UI.

    Args:
        graph: A compiled LangGraph graph (result of StateGraph.compile()).
        opik_tracer: An OpikTracer instance to use for tracking the graph.

    Returns:
        The modified graph with Opik tracking enabled.

    Example:
        ```python
        from langgraph.graph import StateGraph, START, END
        from opik.integrations.langchain import OpikTracer, track_langgraph

        # Build your graph
        builder = StateGraph(State)
        builder.add_node("my_node", my_node_function)
        builder.add_edge(START, "my_node")
        builder.add_edge("my_node", END)

        # Compile the graph
        graph = builder.compile()

        # Create OpikTracer and track the graph once
        # No need to manually extract the graph - it's done automatically!
        opik_tracer = OpikTracer(
            tags=["production"],
            metadata={"version": "1.0"}
        )
        graph = track_langgraph(graph, opik_tracer)

        # Now all invocations are tracked automatically
        result = graph.invoke({"message": "Hello"})
        # No need to pass config={"callbacks": [opik_tracer]} anymore!
        ```

    Note:
        - The graph visualization is automatically extracted and added to trace metadata
          if not already provided in the OpikTracer constructor.
        - If you need to customize the OpikTracer for specific invocations, you can still
          pass it explicitly in the config parameter, which will override the default.
        - The graph object is modified in-place and also returned for convenience.
        - For async invocations using `ainvoke()`, you may still need to use
          `extract_current_langgraph_span_data()` to propagate context to @track-decorated
          functions within async nodes.
    """
    graph_structure = graph.get_graph(xray=True)
    opik_tracer.set_graph(graph_structure)

    # Inject the callback into the graph's default config
    config: Dict[str, Any] = getattr(graph, "config", None) or {}
    graph.config = config  # type: ignore[attr-defined]
    callbacks: List[Any] = config.setdefault("callbacks", [])

    if any(isinstance(cb, opik_tracer_module.OpikTracer) for cb in callbacks):
        LOGGER.warning(
            "Graph already has an OpikTracer callback injected. "
            "Skipping re-tracking to avoid duplicate callbacks."
        )
        return graph

    callbacks.append(opik_tracer)

    return graph
