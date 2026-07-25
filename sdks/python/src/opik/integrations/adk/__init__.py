import google.adk
from opik import semantic_version

MINIMUM_ADK_VERSION = "1.3.0"

# Pre-1.3.0 ADK is rejected rather than ignored: the OpenTelemetry tracer this
# integration patches exists on older versions too, so patching appears to work
# and instead yields silently incomplete traces.
if semantic_version.SemanticVersion.parse(google.adk.__version__) < MINIMUM_ADK_VERSION:  # type: ignore
    raise RuntimeError(
        f"opik's google-adk integration requires google-adk >= {MINIMUM_ADK_VERSION}, "
        f"but google-adk {google.adk.__version__} is installed. "
        f"Upgrade with: pip install --upgrade 'google-adk>={MINIMUM_ADK_VERSION}'"
    )

from .opik_tracer import OpikTracer  # noqa: E402
from .recursive_callback_injector import track_adk_agent_recursive  # noqa: E402
from .graph.mermaid_graph_builder import build_mermaid_graph_definition  # noqa: E402


__all__ = ["OpikTracer", "track_adk_agent_recursive", "build_mermaid_graph_definition"]
