import logging

from openai.types import responses as openai_responses

from opik.integrations.openai import response_events_aggregator


def test_aggregate_non_terminal_events_returns_none_without_error(caplog):
    event = openai_responses.ResponseCreatedEvent.model_construct(
        response=None,
        sequence_number=0,
        type="response.created",
    )

    with caplog.at_level(logging.DEBUG, logger=response_events_aggregator.LOGGER.name):
        result = response_events_aggregator.aggregate([event])

    assert result is None
    assert not [record for record in caplog.records if record.levelno >= logging.ERROR]
    assert not any(
        "list index out of range" in record.message for record in caplog.records
    )
