"""Utilities for writing pytest episode artifacts for CI."""

from __future__ import annotations

import datetime
import json
import pathlib
from typing import Any, Dict, List, Optional

import pytest

from opik.simulation.episode import EpisodeResult


def build_episode_artifact(
    reports_by_nodeid: Dict[str, pytest.TestReport],
    episodes_by_nodeid: Dict[str, EpisodeResult],
) -> Dict[str, Any]:
    records: List[Dict[str, Any]] = []
    episode_passed = 0

    for nodeid, episode in episodes_by_nodeid.items():
        report = reports_by_nodeid.get(nodeid)
        report_passed = bool(report.passed) if report is not None else False
        episode_is_passing = episode.is_passing() and report_passed
        if episode_is_passing:
            episode_passed += 1

        records.append(
            {
                "nodeid": nodeid,
                "pytest_passed": report_passed,
                "episode_passed": episode_is_passing,
                "episode": episode.model_dump(),
            }
        )

    return {
        "generated_at": datetime.datetime.now(datetime.timezone.utc).isoformat(),
        "episodes_total": len(episodes_by_nodeid),
        "episodes_passed": episode_passed,
        "episodes_failed": len(episodes_by_nodeid) - episode_passed,
        "results": records,
    }


def write_episode_artifact(
    path: str,
    reports_by_nodeid: Dict[str, pytest.TestReport],
    episodes_by_nodeid: Dict[str, EpisodeResult],
) -> Optional[str]:
    if not episodes_by_nodeid:
        return None

    payload = build_episode_artifact(
        reports_by_nodeid=reports_by_nodeid, episodes_by_nodeid=episodes_by_nodeid
    )
    file_path = pathlib.Path(path)
    file_path.parent.mkdir(parents=True, exist_ok=True)
    file_path.write_text(json.dumps(payload, indent=2, sort_keys=True))
    return str(file_path)
