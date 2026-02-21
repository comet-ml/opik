"""Utilities for writing pytest episode artifacts for CI."""

from __future__ import annotations

import datetime
import json
import pathlib
import tempfile
from typing import Any, Dict, List, Optional

import pytest

from opik.simulation.episode import EpisodeResult


def build_episode_artifact(
    reports_by_nodeid: Dict[str, pytest.TestReport],
    episodes_by_nodeid: Dict[str, EpisodeResult],
) -> Dict[str, Any]:
    records: List[Dict[str, Any]] = []
    episode_passed = 0
    episode_total = 0

    for nodeid, episode in episodes_by_nodeid.items():
        report = reports_by_nodeid.get(nodeid)
        if report is None:
            continue
        episode_total += 1
        report_passed = bool(report.passed)
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
        "episodes_total": episode_total,
        "episodes_passed": episode_passed,
        "episodes_failed": episode_total - episode_passed,
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
    file_path = _resolve_safe_relative_path(path)
    file_path.parent.mkdir(parents=True, exist_ok=True)
    serialized_payload = json.dumps(payload, indent=2, sort_keys=True)

    with tempfile.NamedTemporaryFile(
        mode="w",
        encoding="utf-8",
        delete=False,
        dir=file_path.parent,
        prefix=f".{file_path.name}.",
    ) as tmp_file:
        tmp_file.write(serialized_payload)
        temp_path = pathlib.Path(tmp_file.name)

    temp_path.replace(file_path)
    return str(file_path)


def _resolve_safe_relative_path(path: str) -> pathlib.Path:
    file_path = pathlib.Path(path)

    if file_path.is_absolute():
        raise ValueError("pytest episode artifact path must be relative")

    if any(part == ".." for part in file_path.parts):
        raise ValueError("pytest episode artifact path must not contain '..'")

    return file_path
