"""Pytest fixtures for log suppression in expected-warning paths."""

from __future__ import annotations

import logging

import pytest


@pytest.fixture
def suppress_expected_optimizer_warnings(caplog: pytest.LogCaptureFixture) -> None:
    loggers = [
        "opik_optimizer.algorithms.meta_prompt_optimizer.ops.candidate_ops",
        "opik_optimizer.algorithms.meta_prompt_optimizer.ops.halloffame_ops",
        "opik_optimizer.algorithms.evolutionary_optimizer.ops.mutation_ops",
        "opik_optimizer.utils.tools.wikipedia",
    ]
    for name in loggers:
        caplog.set_level(logging.ERROR, logger=name)

