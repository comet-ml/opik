"""Atomic Agents integration package for Opik SDK.

This package provides utilities that allow Opik to trace and analyse Atomic Agents workflows.
Concrete implementation will be added incrementally.
"""

from __future__ import annotations

from .context_providers import OpikContextProvider
from .decorators import track_atomic_agents
from .opik_tracer import OpikAtomicAgentsTracer

__all__ = [
    "OpikAtomicAgentsTracer",
    "track_atomic_agents",
    "OpikContextProvider",
]
