"""Optional agent-trace setup must not prevent HRPO from optimizing."""

from contextlib import contextmanager
from collections.abc import Iterator
from typing import Any

import opik
import pytest

from tests.unit.characterization import harness


def test_hrpo_continues_when_local_recording_setup_fails(
    install_fakes: Any,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    scenario = harness.build_scenario("S1_single")
    install_fakes(scenario)

    @contextmanager
    def unavailable() -> Iterator[Any]:
        raise OSError("local recording unavailable")
        yield  # pragma: no cover

    monkeypatch.setattr(opik, "record_traces_locally", unavailable)
    result = harness.run_optimizer("HierarchicalReflectiveOptimizer", scenario)
    assert result.score > 0
    assert result.details["finish_reason"]
