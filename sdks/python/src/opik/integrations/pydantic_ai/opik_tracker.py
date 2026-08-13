from typing import Any, Dict, List, Optional, TypeVar, overload

from opentelemetry.sdk.trace import TracerProvider
from pydantic_ai import Agent, InstrumentationSettings

from .opik_span_processor import OpikSpanProcessor

AgentDepsT = TypeVar("AgentDepsT")
OutputDataT = TypeVar("OutputDataT")


@overload
def track_pydantic_ai(
    agent: Agent[AgentDepsT, OutputDataT],
    *,
    project_name: Optional[str] = None,
    metadata: Optional[Dict[str, Any]] = None,
    tags: Optional[List[str]] = None,
) -> Agent[AgentDepsT, OutputDataT]: ...


@overload
def track_pydantic_ai(
    agent: None = None,
    *,
    project_name: Optional[str] = None,
    metadata: Optional[Dict[str, Any]] = None,
    tags: Optional[List[str]] = None,
) -> None: ...


def track_pydantic_ai(
    agent: Optional[Agent[AgentDepsT, OutputDataT]] = None,
    *,
    project_name: Optional[str] = None,
    metadata: Optional[Dict[str, Any]] = None,
    tags: Optional[List[str]] = None,
) -> Optional[Agent[AgentDepsT, OutputDataT]]:
    """Enable Opik tracing for Pydantic AI agents.

    Args:
        agent: A single ``pydantic_ai.Agent`` to instrument. When ``None`` (default),
            every agent is instrumented via ``Agent.instrument_all``.
        project_name: Opik project for traces created by standalone runs (no active
            ``@opik.track`` trace).
        metadata: Default metadata applied to the trace/root span.
        tags: Default tags applied to the trace/root span.

    Returns:
        The instrumented agent when one is passed, otherwise ``None``.
    """
    provider = TracerProvider()
    provider.add_span_processor(
        OpikSpanProcessor(project_name=project_name, metadata=metadata, tags=tags)
    )
    settings = InstrumentationSettings(tracer_provider=provider)

    if agent is None:
        Agent.instrument_all(settings)
        return None

    agent.instrument = settings
    return agent
