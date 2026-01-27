"""Pareto/MOO helpers for evolutionary optimization.

NOTE: These helpers are intentionally isolated so they can be reused by
other optimizers that want Pareto or NSGA-II style selection later.
"""

from __future__ import annotations

from typing import Any
import logging

try:
    from deap import tools

    _DEAP_IMPORT_ERROR: Exception | None = None
except Exception as exc:  # pragma: no cover - exercised when DEAP is missing
    tools = None
    _DEAP_IMPORT_ERROR = exc

logger = logging.getLogger(__name__)


# TODO: Move into a shared optimizer-agnostic Pareto helper module.


def _require_deap() -> None:
    if tools is None:
        raise RuntimeError(
            "DEAP is required for EvolutionaryOptimizer. "
            "Install the optimizer extras that include DEAP."
        ) from _DEAP_IMPORT_ERROR


# TODO: Decide whether selection policies should be centralized for all optimizers.
def select_population(
    *,
    population: list[Any],
    population_size: int,
    elitism_size: int,
    tournament_size: int,
    enable_moo: bool,
) -> list[Any]:
    _require_deap()
    if enable_moo:
        return tools.selNSGA2(population, population_size)
    elites = tools.selBest(population, elitism_size)
    rest = tools.selTournament(
        population,
        len(population) - elitism_size,
        tournsize=tournament_size,
    )
    return elites + rest


# TODO: Use a shared candidate schema once history standardization is complete.


def build_pareto_front(
    hof: tools.HallOfFame | tools.ParetoFront, generation_idx: int
) -> list[dict[str, Any]]:
    _require_deap()
    if not hof:
        return []
    return [
        {
            "score": ind.fitness.values[0],
            "length": ind.fitness.values[1],
            "id": f"gen{generation_idx}_hof{idx}",
        }
        for idx, ind in enumerate(hof)
    ]


def selection_meta(
    *,
    selection_policy: str,
    pareto_front: list[dict[str, Any]] | None,
) -> dict[str, Any] | None:
    if pareto_front is None:
        return None
    return {
        "selection_policy": selection_policy,
        "pareto_front": pareto_front,
    }


# TODO: Support configurable tiebreakers when multiple solutions share the same score.
def choose_best_from_front(
    *,
    hof: tools.HallOfFame | tools.ParetoFront,
    enable_moo: bool,
) -> Any | None:
    _require_deap()
    if not hof:
        return None
    if enable_moo:
        return max(hof, key=lambda ind: ind.fitness.values[0])
    return hof[0]


def serialize_pareto_solutions(
    hof: tools.HallOfFame | tools.ParetoFront,
) -> list[dict[str, Any]]:
    _require_deap()
    if not hof:
        return []
    return [
        {
            "prompt": str(dict(ind)),
            "score": ind.fitness.values[0],
            "length": ind.fitness.values[1],
        }
        for ind in hof
    ]


def format_pareto_log(hof: tools.HallOfFame | tools.ParetoFront) -> str:
    _require_deap()
    if not hof:
        return "Pareto Front Solutions: <empty>"
    sorted_hof = sorted(hof, key=lambda ind: ind.fitness.values[0], reverse=True)
    lines = ["Pareto Front Solutions:"]
    for i, sol in enumerate(sorted_hof):
        lines.append(
            f"  Solution {i + 1}: Primary Score={sol.fitness.values[0]:.4f}, "
            f"Length={sol.fitness.values[1]:.0f}, Prompts={len(sol)}"
        )
    return "\n".join(lines)
