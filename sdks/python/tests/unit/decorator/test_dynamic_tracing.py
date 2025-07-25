import importlib
from unittest import mock
from typing import Generator

import pytest

import opik

runtime_config = importlib.import_module("opik.decorator.tracing_runtime_config")


@pytest.fixture(autouse=True)
def reset_tracing_state() -> Generator[None, None, None]:
    runtime_config.reset_tracing_to_config_default()
    yield
    runtime_config.reset_tracing_to_config_default()


@opik.track(name="add_numbers")
def add_numbers(x: int, y: int) -> int:
    return x + y


def test_track_decorator_respects_runtime_flag() -> None:
    opik.set_tracing_active(False)

    with mock.patch(
        "opik.decorator.span_creation_handler.create_span_respecting_context"
    ) as mocked_create:
        result = add_numbers(1, 2)
        assert result == 3
        assert not mocked_create.called

    opik.set_tracing_active(True)

    with mock.patch(
        "opik.decorator.span_creation_handler.create_span_respecting_context"
    ) as mocked_create:
        result = add_numbers(4, 5)
        assert result == 9
        assert mocked_create.called
