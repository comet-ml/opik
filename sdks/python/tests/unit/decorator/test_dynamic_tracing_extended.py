import asyncio
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


@opik.track(name="async_add")
async def async_add(x: int, y: int) -> int:
    await asyncio.sleep(0.001)
    return x + y


def test_track_async_function_respects_runtime_flag():
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


def test_track_generator_function_respects_runtime_flag():
    opik.set_tracing_active(False)
    with mock.patch(
        "opik.decorator.span_creation_handler.create_span_respecting_context",
        return_value=(None, mock.Mock()),
    ) as mocked_create:
        for _ in gen_numbers(3):
            pass
        assert not mocked_create.called

    opik.set_tracing_active(True)
    with mock.patch(
        "opik.decorator.span_creation_handler.create_span_respecting_context",
        return_value=(None, mock.Mock()),
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


def test_track_async_generator_function_respects_runtime_flag():
    opik.set_tracing_active(False)
    with mock.patch(
        "opik.decorator.span_creation_handler.create_span_respecting_context",
        return_value=(None, mock.Mock()),
    ) as mocked_create:
        asyncio.run(_consume_async_gen(3))
        assert not mocked_create.called

    opik.set_tracing_active(True)
    with mock.patch(
        "opik.decorator.span_creation_handler.create_span_respecting_context",
        return_value=(None, mock.Mock()),
    ) as mocked_create:
        asyncio.run(_consume_async_gen(2))
        assert mocked_create.called
