"""Shared helpers for evolutionary crossover ops unit tests."""

from __future__ import annotations

from typing import Any


def make_deap_individual(data: dict[str, Any]) -> Any:
    """
    Build a DEAP Individual(dict) for crossover tests.

    Ensures the creator class exists exactly once.
    """
    from deap import creator

    if not hasattr(creator, "Individual"):
        creator.create("Individual", dict, fitness=None)
    return creator.Individual(data)

