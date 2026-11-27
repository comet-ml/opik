import logging

import pytest
from flask import Flask

from opik_backend import evaluator, executor_docker
from opik_backend.executor_process import PROCESS_NETWORK_ISOLATION_ENV_VAR


def _warnings(caplog) -> list[str]:
    return [r.getMessage() for r in caplog.records if r.name == evaluator.__name__ and r.levelno == logging.WARNING]


@pytest.mark.parametrize(
    "isolation, expected",
    [
        ("true", evaluator.PROCESS_STRATEGY_WARNING),
        ("false", evaluator.UNISOLATED_PROCESS_STRATEGY_WARNING),
    ],
)
def test_process_strategy_warns_about_its_isolation(monkeypatch, caplog, isolation, expected):
    monkeypatch.setattr(evaluator, "EXECUTION_STRATEGY", "process")
    monkeypatch.setenv(PROCESS_NETWORK_ISOLATION_ENV_VAR, isolation)
    app = Flask(__name__)
    app.debug = True  # keeps the worker pool from starting

    with caplog.at_level(logging.WARNING):
        evaluator.init_executor(app)

    assert _warnings(caplog) == [expected]


def test_docker_strategy_warns_that_it_needs_privileged(monkeypatch, caplog):
    monkeypatch.setattr(evaluator, "EXECUTION_STRATEGY", "docker")
    monkeypatch.setattr(executor_docker, "DockerExecutor", object)

    with caplog.at_level(logging.WARNING):
        evaluator.init_executor(Flask(__name__))

    assert _warnings(caplog) == [evaluator.DOCKER_STRATEGY_WARNING]
