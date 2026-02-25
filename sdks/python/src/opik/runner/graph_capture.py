"""Graph capture collector for debug stepping.

When active, records each @track function call as a graph node
so the debug session can replay individual functions.
"""

import inspect
import threading
from dataclasses import dataclass, field, asdict
from typing import Any, Dict, List, Optional


@dataclass
class GraphNode:
    node_id: str
    function_name: str
    qualified_name: str
    module_path: str
    inputs: Dict[str, Any]
    output: Any = None
    span_id: str = ""
    trace_id: str = ""
    parent_span_id: Optional[str] = None
    call_order: int = 0
    type: str = "general"


_lock = threading.Lock()
_active = False
_nodes: List[GraphNode] = []
_counter = 0


def activate() -> None:
    global _active, _nodes, _counter
    with _lock:
        _active = True
        _nodes = []
        _counter = 0


def deactivate() -> None:
    global _active
    with _lock:
        _active = False


def is_active() -> bool:
    return _active


def record_before(
    func: Any,
    inputs: Dict[str, Any],
    span_id: str,
    trace_id: str,
    parent_span_id: Optional[str],
    span_type: str = "general",
) -> str:
    global _counter
    with _lock:
        order = _counter
        _counter += 1

    try:
        module_path = inspect.getfile(func)
    except (TypeError, OSError):
        module_path = ""

    qualified_name = getattr(func, "__module__", "") + "." + getattr(func, "__qualname__", func.__name__)

    node = GraphNode(
        node_id=span_id,
        function_name=func.__name__,
        qualified_name=qualified_name,
        module_path=module_path,
        inputs=inputs,
        span_id=span_id,
        trace_id=trace_id,
        parent_span_id=parent_span_id,
        call_order=order,
        type=span_type,
    )

    with _lock:
        _nodes.append(node)

    return span_id


def record_after(node_id: str, output: Any) -> None:
    with _lock:
        for node in reversed(_nodes):
            if node.node_id == node_id:
                node.output = output
                break


def get_graph() -> List[GraphNode]:
    with _lock:
        return list(_nodes)


def get_graph_dicts() -> List[Dict[str, Any]]:
    with _lock:
        return [asdict(n) for n in _nodes]
