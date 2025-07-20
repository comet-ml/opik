"""Atomic Agents integration package for Opik SDK.

This package provides utilities that allow Opik to trace and analyse Atomic Agents workflows.
Concrete implementation will be added incrementally.
"""

from __future__ import annotations

from .opik_tracer import OpikAtomicAgentsTracer  # noqa: F401
from .decorators import track_atomic_agents  # noqa: F401
from .context_providers import OpikContextProvider  # noqa: F401

__all__ = [
    "OpikAtomicAgentsTracer",
]
__all__.append("track_atomic_agents")
__all__.append("OpikContextProvider") 