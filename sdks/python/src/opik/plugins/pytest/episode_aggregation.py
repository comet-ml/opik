from dataclasses import dataclass
from typing import Any, Dict, List

import pytest

from opik.simulation.episode import EpisodeResult


@dataclass
class EpisodeAggregation:
    total: int
    passed: int
    failed: int
    failed_scenarios: List[str]
    records: List[Dict[str, Any]]


def aggregate_episode_results(
    reports_by_nodeid: Dict[str, pytest.TestReport],
    episodes_by_nodeid: Dict[str, EpisodeResult],
) -> EpisodeAggregation:
    records: List[Dict[str, Any]] = []
    passed = 0
    total = 0
    failed_scenarios: List[str] = []

    for nodeid, episode in episodes_by_nodeid.items():
        report = reports_by_nodeid.get(nodeid)
        if report is None:
            continue

        total += 1
        pytest_passed = bool(report.passed)
        episode_passed = episode.is_passing() and pytest_passed
        if episode_passed:
            passed += 1
        else:
            failed_scenarios.append(episode.scenario_id)

        records.append(
            {
                "nodeid": nodeid,
                "pytest_passed": pytest_passed,
                "episode_passed": episode_passed,
                "episode": episode.model_dump(),
            }
        )

    return EpisodeAggregation(
        total=total,
        passed=passed,
        failed=total - passed,
        failed_scenarios=failed_scenarios,
        records=records,
    )
