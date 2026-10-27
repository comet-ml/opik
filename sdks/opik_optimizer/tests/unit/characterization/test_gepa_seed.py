"""GEPA's search uses the seed the optimizer was constructed with."""

from __future__ import annotations

from typing import Any

import gepa
import pytest

from opik_optimizer import GepaOptimizer
from tests.unit.characterization import harness


@pytest.mark.parametrize("seed", [7, 123])
def test_gepa_search_uses_the_optimizer_seed(
    seed: int, install_fakes: Any, monkeypatch: pytest.MonkeyPatch
) -> None:
    scenario = harness.build_scenario("S1_single")
    install_fakes(scenario)
    seen: list[int] = []
    original = gepa.optimize

    def spy(**kwargs: Any) -> Any:
        seen.append(kwargs["seed"])
        return original(**kwargs)

    monkeypatch.setattr(gepa, "optimize", spy)
    optimizer = GepaOptimizer(model=harness.MODEL, n_threads=1, seed=seed, verbose=0)

    harness.run_optimizer("GepaOptimizer", scenario, optimizer=optimizer)

    assert seen == [seed]
