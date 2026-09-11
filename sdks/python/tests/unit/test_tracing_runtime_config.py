import asyncio
import importlib
from typing import Any, Generator
from unittest import mock

import pytest

import opik
from opik.decorator import span_creation_handler

runtime_config = importlib.import_module("opik.tracing_runtime_config")


@pytest.fixture(autouse=True)
def reset_tracing_state() -> Generator[None, None, None]:
    runtime_config.reset_tracing_to_config_default()
    yield
    runtime_config.reset_tracing_to_config_default()


@pytest.mark.parametrize("first,second", [(False, True), (True, False)])
def test_set_and_get__both_states__works_correctly(first: bool, second: bool) -> None:
    opik.set_tracing_active(first)
    assert opik.is_tracing_active() is first

    opik.set_tracing_active(second)
    assert opik.is_tracing_active() is second


def test_reset_to_config_default__happyflow(monkeypatch: Any) -> None:
    from opik import config as _config_module

    class DummyConfig(_config_module.OpikConfig):
        track_disable: bool = True

    monkeypatch.setattr(_config_module, "OpikConfig", DummyConfig, raising=False)
    importlib.reload(runtime_config)

    assert opik.is_tracing_active() is False

    opik.set_tracing_active(True)
    assert opik.is_tracing_active() is True

    opik.reset_tracing_to_config_default()
    assert opik.is_tracing_active() is False


@opik.track(name="add_numbers")
def add_numbers(x: int, y: int) -> int:
    return x + y


def test_track_decorator__sync_function__respects_runtime_flag() -> None:
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


@opik.track(name="async_add")
async def async_add(x: int, y: int) -> int:
    await asyncio.sleep(0.001)
    return x + y


def test_track_decorator__async_function__respects_runtime_flag():
    opik.set_tracing_active(False)
    with mock.patch(
        "opik.decorator.span_creation_handler.create_span_respecting_context",
        return_value=(None, mock.Mock()),
    ) as mocked_create:
        asyncio.run(async_add(1, 2))
        assert not mocked_create.called

    opik.set_tracing_active(True)
    with mock.patch(
        "opik.decorator.span_creation_handler.create_span_respecting_context",
        return_value=(None, mock.Mock()),
    ) as mocked_create:
        asyncio.run(async_add(3, 4))
        assert mocked_create.called


@opik.track(name="gen_numbers")
def gen_numbers(limit: int):
    for i in range(limit):
        yield i


def test_track_decorator__generator_function__respects_runtime_flag():
    opik.set_tracing_active(False)
    with mock.patch(
        "opik.decorator.span_creation_handler.create_span_respecting_context",
        return_value=span_creation_handler.SpanCreationResult(
            None, mock.Mock(), should_process_span_data=True
        ),
    ) as mocked_create:
        for _ in gen_numbers(3):
            pass
        assert not mocked_create.called

    opik.set_tracing_active(True)
    with mock.patch(
        "opik.decorator.span_creation_handler.create_span_respecting_context",
        return_value=span_creation_handler.SpanCreationResult(
            None, mock.Mock(), should_process_span_data=True
        ),
    ) as mocked_create:
        for _ in gen_numbers(2):
            pass
        assert mocked_create.called


@opik.track(name="async_gen_numbers")
async def async_gen_numbers(limit: int):
    for i in range(limit):
        yield i


async def _consume_async_gen(limit: int):
    async for _ in async_gen_numbers(limit):
        pass


def test_track_decorator__async_generator_function__respects_runtime_flag():
    opik.set_tracing_active(False)
    with mock.patch(
        "opik.decorator.span_creation_handler.create_span_respecting_context",
        return_value=span_creation_handler.SpanCreationResult(
            None, mock.Mock(), should_process_span_data=True
        ),
    ) as mocked_create:
        asyncio.run(_consume_async_gen(3))
        assert not mocked_create.called

    opik.set_tracing_active(True)
    with mock.patch(
        "opik.decorator.span_creation_handler.create_span_respecting_context",
        return_value=span_creation_handler.SpanCreationResult(
            None, mock.Mock(), should_process_span_data=True
        ),
    ) as mocked_create:
        asyncio.run(_consume_async_gen(2))
        assert mocked_create.called
