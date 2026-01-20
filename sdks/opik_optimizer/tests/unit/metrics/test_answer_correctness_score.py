from __future__ import annotations

from importlib.util import module_from_spec, spec_from_file_location
from pathlib import Path
from typing import Protocol, cast
from collections.abc import Callable, Mapping

import pytest


class _MetricsModule(Protocol):
    answer_correctness_score: Callable[[Mapping[str, object], str], object]


def _load_metrics_module() -> object:
    metrics_path = (
        Path(__file__).resolve().parents[3]
        / "scripts"
        / "optimizer_algorithms"
        / "utils"
        / "metrics.py"
    )
    spec = spec_from_file_location("optimizer_algorithms_metrics", metrics_path)
    if spec is None or spec.loader is None:
        raise RuntimeError("Unable to load metrics module for tests.")
    module = module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def test_answer_correctness_score_requires_answer_field() -> None:
    metrics_module = cast(_MetricsModule, _load_metrics_module())
    dataset_item = {"question": "Q1"}
    with pytest.raises(ValueError, match="requires dataset items with an 'answer'"):
        metrics_module.answer_correctness_score(dataset_item, "output")
