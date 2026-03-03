"""Utilities for writing pytest episode artifacts for CI."""

from __future__ import annotations

import datetime
import json
import pathlib
import tempfile
from typing import Any, Dict, Optional

import pytest

from . import episode_aggregation
from opik.simulation.episode import EpisodeResult


def build_episode_artifact(
    reports_by_nodeid: Dict[str, pytest.TestReport],
    episodes_by_nodeid: Dict[str, EpisodeResult],
) -> Dict[str, Any]:
    aggregation = episode_aggregation.aggregate_episode_results(
        reports_by_nodeid=reports_by_nodeid,
        episodes_by_nodeid=episodes_by_nodeid,
    )

    return {
        "generated_at": datetime.datetime.now(datetime.timezone.utc).isoformat(),
        "episodes_total": aggregation.total,
        "episodes_passed": aggregation.passed,
        "episodes_failed": aggregation.failed,
        "results": aggregation.records,
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
