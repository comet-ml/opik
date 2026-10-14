"""An OpenAI Responses stream abandoned before its terminal event logs no error.

Stopping early is ordinary: a `break` once enough has arrived, a cancelled task,
a client that disconnects. Such a stream never delivers `response.completed`,
so there is no response to record, and that is expected rather than a failure.
"""

import asyncio
import logging
from typing import Iterator, List

import openai
import pytest
from openai.types import responses as openai_responses

import opik
from opik.integrations.openai import response_events_aggregator, track_openai

from ... import llm_constants
from .constants import MODEL_FOR_TESTS


@pytest.fixture
def aggregator_log_records() -> Iterator[List[logging.LogRecord]]:
    """Records written to the aggregator's own logger.

    Opik's loggers don't propagate to the root logger, so `caplog` never sees
    them; a handler on the module logger does.
    """
    records: List[logging.LogRecord] = []

    class _Capture(logging.Handler):
        def emit(self, record: logging.LogRecord) -> None:
            records.append(record)

    logger = response_events_aggregator.LOGGER
    handler = _Capture(level=logging.DEBUG)
    previous_level = logger.level
    logger.addHandler(handler)
    logger.setLevel(logging.DEBUG)
    try:
        yield records
    finally:
        logger.removeHandler(handler)
        logger.setLevel(previous_level)


def _levels(records: List[logging.LogRecord]) -> List[int]:
    return [record.levelno for record in records]


def _create_responses_stream(client):
    return client.responses.create(
        model=MODEL_FOR_TESTS,
        input="Count from 1 to 20.",
        max_output_tokens=256,
        reasoning={"effort": llm_constants.OPENAI_REASONING_EFFORT},
        stream=True,
    )


def test_aggregate__non_terminal_events_only__returns_none_and_logs_debug(
    aggregator_log_records,
):
    event = openai_responses.ResponseCreatedEvent.model_construct(
        response=None,
        sequence_number=0,
        type="response.created",
    )

    result = response_events_aggregator.aggregate([event])

    assert result is None
    assert _levels(aggregator_log_records) == [logging.DEBUG]


@pytest.mark.usefixtures("ensure_openai_configured")
def test_openai_responses_stream__consumer_breaks_early__no_error_logged(
    fake_backend, aggregator_log_records
):
    client = track_openai(openai.OpenAI())

    for _ in _create_responses_stream(client):
        break

    opik.flush_tracker()

    responses_span = fake_backend.trace_trees[0].spans[0]
    assert responses_span.end_time is not None
    assert logging.ERROR not in _levels(aggregator_log_records)


@pytest.mark.asyncio
@pytest.mark.usefixtures("ensure_openai_configured")
async def test_openai_async_responses_stream__consumer_breaks_early__no_error_logged(
    fake_backend, aggregator_log_records
):
    client = track_openai(openai.AsyncOpenAI())
    stream = await _create_responses_stream(client)

    async for _ in stream:
        break
    # asyncio closes the dropped async iterator on its next turn.
    await asyncio.sleep(0.05)

    opik.flush_tracker()

    responses_span = fake_backend.trace_trees[0].spans[0]
    assert responses_span.end_time is not None
    assert logging.ERROR not in _levels(aggregator_log_records)
