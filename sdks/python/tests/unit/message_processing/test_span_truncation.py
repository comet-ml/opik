import datetime as dt
from unittest import mock


from opik.message_processing import span_truncation, messages
from opik.message_processing.processors import online_message_processor
from opik.rest_api.types import span_write

from ...testlib import fake_message_factory

ONE_MEGABYTE = fake_message_factory.ONE_MEGABYTE
LIMIT_MB = 20.0


def _span_write(**fields) -> span_write.SpanWrite:
    return span_write.SpanWrite(
        id="span-id",
        trace_id="trace-id",
        name="my-span",
        start_time=dt.datetime.now(tz=dt.timezone.utc),
        **fields,
    )


def _big_value(megabytes: int):
    return {"payload": "x" * (megabytes * ONE_MEGABYTE)}


# --------------------------------------------------------------------------- #
# span_truncation module
# --------------------------------------------------------------------------- #


def test_truncate_span_write__within_limit__returned_unchanged():
    span = _span_write(input={"prompt": "small"}, output={"result": "small"})

    result = span_truncation.truncate_span_write_if_needed(span, LIMIT_MB)

    assert result is span  # identity preserved, nothing copied
    assert result.input == {"prompt": "small"}


def test_truncate_span_write__oversized_output__truncated(caplog):
    span = _span_write(input={"prompt": "small"}, output=_big_value(21))

    with caplog.at_level("WARNING"):
        result = span_truncation.truncate_span_write_if_needed(span, LIMIT_MB)

    # the oversized field is replaced with a marker, the small one is untouched
    assert result.output["opik_truncated"] is True
    assert result.output["reason"].startswith("<omitted_due_to_size_")
    assert result.input == {"prompt": "small"}
    # a warning naming the span + field was logged
    assert "span-id" in caplog.text and "output" in caplog.text


def test_truncate_span_write__each_oversized_field_truncated_independently():
    # both input and output individually exceed the per-field limit -> both truncated
    span = _span_write(input=_big_value(25), output=_big_value(25))

    result = span_truncation.truncate_span_write_if_needed(span, LIMIT_MB)

    assert result.input["opik_truncated"] is True
    assert result.output["opik_truncated"] is True


def test_truncate_span_write__total_over_but_no_single_field_over__truncates_all():
    # No single field exceeds the limit, but the span total (~30 MB) does -> the
    # hard per-span cap (pass 2) kicks in and truncates all truncatable fields.
    span = _span_write(input=_big_value(15), output=_big_value(15))

    result = span_truncation.truncate_span_write_if_needed(span, LIMIT_MB)

    assert result.input["opik_truncated"] is True
    assert result.output["opik_truncated"] is True


def test_truncate_span_write__metadata_never_truncated_and_does_not_trigger_others():
    # metadata is deliberately exempt: a huge metadata must NOT be truncated, and
    # must NOT drag the span "over" the cap and cause small input/output to be cut.
    span = _span_write(
        input={"prompt": "small"},
        output={"result": "small"},
        metadata=_big_value(25),
    )

    result = span_truncation.truncate_span_write_if_needed(span, LIMIT_MB)

    assert result is span  # nothing truncated at all
    assert result.metadata == _big_value(25)  # metadata left fully intact
    assert result.input == {"prompt": "small"}
    assert result.output == {"result": "small"}


def test_truncate_span_write__oversized_input_truncated_but_metadata_kept():
    # input (truncatable) is capped; metadata (exempt) is preserved even when huge.
    span = _span_write(input=_big_value(25), metadata=_big_value(25))

    result = span_truncation.truncate_span_write_if_needed(span, LIMIT_MB)

    assert result.input["opik_truncated"] is True
    assert result.metadata == _big_value(25)


def test_truncate_span_write__original_is_not_mutated():
    span = _span_write(output=_big_value(21))
    original_output = span.output

    span_truncation.truncate_span_write_if_needed(span, LIMIT_MB)

    assert span.output is original_output  # frozen model untouched


def test_truncate_span_kwargs__oversized__truncated_in_place(caplog):
    kwargs = {"id": "span-id", "output": _big_value(21), "input": {"prompt": "small"}}

    with caplog.at_level("WARNING"):
        span_truncation.truncate_span_kwargs_if_needed(kwargs, LIMIT_MB)

    assert kwargs["output"]["opik_truncated"] is True
    assert kwargs["input"] == {"prompt": "small"}
    assert "span-id" in caplog.text


def test_truncate_span_kwargs__within_limit__untouched():
    kwargs = {"id": "span-id", "output": {"result": "small"}}
    span_truncation.truncate_span_kwargs_if_needed(kwargs, LIMIT_MB)
    assert kwargs["output"] == {"result": "small"}


# --------------------------------------------------------------------------- #
# processor integration — driven through the public `process()` dispatch (not the
# private per-message handlers); asserts on the payload the processor forwards to
# the REST client, which is where truncation must happen (right before the BE send).
# --------------------------------------------------------------------------- #


def _processor(max_span_payload_size_mb):
    return online_message_processor.OpikMessageProcessor(
        rest_client=mock.MagicMock(),
        file_upload_manager=mock.MagicMock(),
        fallback_replay_manager=mock.MagicMock(),
        unauthorized_message_types_registry=mock.MagicMock(),
        max_span_payload_size_mb=max_span_payload_size_mb,
    )


def test_process_create_spans_batch__oversized_span_truncated_before_send():
    processor = _processor(max_span_payload_size_mb=LIMIT_MB)
    big_span = _span_write(output=_big_value(21))
    small_span = _span_write(output={"result": "small"})
    message = messages.CreateSpansBatchMessage(batch=[big_span, small_span])

    processor.process(message)

    sent = processor._rest_client.spans.create_spans.call_args.kwargs["spans"]
    assert sent[0].output["opik_truncated"] is True  # oversized span truncated
    assert sent[1].output == {"result": "small"}  # small span passed through


def test_process_create_spans_batch__limit_disabled__no_truncation():
    processor = _processor(max_span_payload_size_mb=None)
    big_span = _span_write(output=_big_value(21))
    message = messages.CreateSpansBatchMessage(batch=[big_span])

    processor.process(message)

    sent = processor._rest_client.spans.create_spans.call_args.kwargs["spans"]
    assert sent[0].output == big_span.output  # unchanged when disabled


def test_process_create_span__oversized_field_truncated_before_send():
    processor = _processor(max_span_payload_size_mb=LIMIT_MB)
    span_message = fake_message_factory.fake_span_create_message_batch(
        count=1, approximate_span_size=21 * ONE_MEGABYTE
    )[0]

    processor.process(span_message)

    sent_kwargs = processor._rest_client.spans.create_span.call_args.kwargs
    # the oversized field was replaced with a truncation marker
    truncated = [
        v
        for k, v in sent_kwargs.items()
        if isinstance(v, dict) and v.get("opik_truncated") is True
    ]
    assert truncated, "expected an oversized field to be truncated"


def _update_span_message(**fields) -> messages.UpdateSpanMessage:
    defaults = dict(
        span_id="span-id",
        parent_span_id=None,
        trace_id="trace-id",
        project_name="my-project",
        end_time=None,
        input=None,
        output=None,
        metadata=None,
        tags=None,
        usage=None,
        model=None,
        provider=None,
        error_info=None,
        total_cost=None,
        source="sdk",
    )
    defaults.update(fields)
    return messages.UpdateSpanMessage(**defaults)


def test_process_update_span__oversized_field_truncated_before_send():
    # An oversized output attached via update_span (e.g. span.end(output=...)
    # after the create was flushed) must be capped, not bypass the limit.
    processor = _processor(max_span_payload_size_mb=LIMIT_MB)
    message = _update_span_message(output=_big_value(21), input={"prompt": "small"})

    processor.process(message)

    sent_kwargs = processor._rest_client.spans.update_span.call_args.kwargs
    assert sent_kwargs["output"]["opik_truncated"] is True
    assert sent_kwargs["input"] == {"prompt": "small"}


def test_process_update_span__limit_disabled__no_truncation():
    processor = _processor(max_span_payload_size_mb=None)
    message = _update_span_message(output=_big_value(21))

    processor.process(message)

    sent_kwargs = processor._rest_client.spans.update_span.call_args.kwargs
    assert sent_kwargs["output"] == _big_value(21)  # unchanged when disabled
