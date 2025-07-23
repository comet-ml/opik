import google.adk
from opik import semantic_version

if semantic_version.SemanticVersion.parse(google.adk.__version__) < "1.3.0":
    from .legacy_opik_tracer import LegacyOpikTracer as OpikTracer
else:
    from .opik_tracer import OpikTracer

from .recursive_callback_injector import track_adk_agent_recursive
from .graph.mermaid_graph_builder import build_mermaid_graph_definition


__all__ = ["OpikTracer", "track_adk_agent_recursive", "build_mermaid_graph_definition"]
