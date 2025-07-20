"""Atomic Agents integration package for Opik SDK.

This package provides utilities that allow Opik to trace and analyse Atomic Agents workflows.
Concrete implementation will be added incrementally.
"""

from __future__ import annotations

from .opik_tracer import OpikAtomicAgentsTracer  # noqa: F401

__all__ = [
    "OpikAtomicAgentsTracer",
] 