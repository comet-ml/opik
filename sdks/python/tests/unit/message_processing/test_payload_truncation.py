import datetime as dt
from unittest import mock


from opik.message_processing import payload_truncation, messages
from opik.message_processing.processors import online_message_processor
from opik.rest_api.types import span_write, trace_write

from ...testlib import fake_message_factory

ONE_MEGABYTE = fake_message_factory.ONE_MEGABYTE
# A small limit keeps the fixtures tiny: a "big" field only needs to be ~1-2 MB to exceed it,
# so the suite doesn't allocate/copy 20+ MB strings just to hit the truncation branch.
LIMIT_MB = 1.0


def _span_write(**fields) -> span_write.SpanWrite:
    return span_write.SpanWrite(
        id="span-id",
        trace_id="trace-id",
        name="my-span",
        start_time=dt.datetime.now(tz=dt.timezone.utc),
        **fields,
    )


def _big_value(megabytes: float):
    """A payload whose serialized size is ~``megabytes`` MB (small on purpose - see LIMIT_MB)."""
    return {"payload": "x" * int(megabytes * ONE_MEGABYTE)}


# --------------------------------------------------------------------------- #
# payload_truncation module
# --------------------------------------------------------------------------- #


def test_truncate_span_write__within_limit__returned_unchanged():
    span = _span_write(input={"prompt": "small"}, output={"result": "small"})

    result = payload_truncation.truncate_write_if_needed(span, LIMIT_MB)

    assert result is span  # identity preserved, nothing copied
    assert result.input == {"prompt": "small"}


def test_truncate_span_write__oversized_output__truncated(caplog):
    span = _span_write(input={"prompt": "small"}, output=_big_value(1.5))

    with caplog.at_level("WARNING"):
        result = payload_truncation.truncate_write_if_needed(span, LIMIT_MB)

    # the oversized field is replaced with a marker, the small one is untouched
    assert result.output["opik_truncated"] is True
    assert result.output["reason"].startswith("<omitted_due_to_size_")
    assert result.input == {"prompt": "small"}
    # a warning naming the span + field was logged
    assert "span-id" in caplog.text and "output" in caplog.text


def test_truncate_span_write__each_oversized_field_truncated_independently():
    # both input and output individually exceed the per-field limit -> both truncated
    span = _span_write(input=_big_value(1.5), output=_big_value(1.5))

    result = payload_truncation.truncate_write_if_needed(span, LIMIT_MB)

    assert result.input["opik_truncated"] is True
    assert result.output["opik_truncated"] is True


def test_truncate_span_write__total_over_but_no_single_field_over__truncates_all():
    # No single field exceeds the limit, but the span total (~1.4 MB) does -> the hard
    # per-span cap (pass 2) kicks in and truncates all truncatable fields.
    span = _span_write(input=_big_value(0.7), output=_big_value(0.7))

    result = payload_truncation.truncate_write_if_needed(span, LIMIT_MB)

    assert result.input["opik_truncated"] is True
    assert result.output["opik_truncated"] is True


def test_truncate_span_write__metadata_never_truncated_and_does_not_trigger_others():
    # metadata is deliberately exempt: a huge metadata must NOT be truncated, and
    # must NOT drag the span "over" the cap and cause small input/output to be cut.
    span = _span_write(
        input={"prompt": "small"},
        output={"result": "small"},
        metadata=_big_value(1.5),
    )

    result = payload_truncation.truncate_write_if_needed(span, LIMIT_MB)

    assert result is span  # nothing truncated at all
    assert result.metadata == _big_value(1.5)  # metadata left fully intact
    assert result.input == {"prompt": "small"}
    assert result.output == {"result": "small"}


def test_truncate_span_write__oversized_input_truncated_but_metadata_kept():
    # input (truncatable) is capped; metadata (exempt) is preserved even when huge.
    span = _span_write(input=_big_value(1.5), metadata=_big_value(1.5))

    result = payload_truncation.truncate_write_if_needed(span, LIMIT_MB)

    assert result.input["opik_truncated"] is True
    assert result.metadata == _big_value(1.5)


def test_truncate_span_write__original_is_not_mutated():
    span = _span_write(output=_big_value(1.5))
    original_output = span.output

    payload_truncation.truncate_write_if_needed(span, LIMIT_MB)

    assert span.output is original_output  # frozen model untouched


def test_truncate_span_write__non_positive_limit__disables():
    # A limit <= 0 disables the check entirely (parity with the TS SDK) rather than
    # marking every field oversized.
    span = _span_write(output=_big_value(1.5))

    assert payload_truncation.truncate_write_if_needed(span, 0) is span
    assert payload_truncation.truncate_write_if_needed(span, -1) is span


def test_truncate_span_kwargs__oversized__truncated_in_place(caplog):
    kwargs = {"id": "span-id", "output": _big_value(1.5), "input": {"prompt": "small"}}

    with caplog.at_level("WARNING"):
        payload_truncation.truncate_kwargs_if_needed(kwargs, LIMIT_MB)

    assert kwargs["output"]["opik_truncated"] is True
    assert kwargs["input"] == {"prompt": "small"}
    assert "span-id" in caplog.text


def test_truncate_span_kwargs__within_limit__untouched():
    kwargs = {"id": "span-id", "output": {"result": "small"}}
    payload_truncation.truncate_kwargs_if_needed(kwargs, LIMIT_MB)
    assert kwargs["output"] == {"result": "small"}


def test_truncate_span_kwargs__non_positive_limit__disables():
    original = _big_value(1.5)
    kwargs = {"id": "span-id", "output": original}

    payload_truncation.truncate_kwargs_if_needed(kwargs, 0)

    assert kwargs["output"] is original  # unchanged when disabled


# --------------------------------------------------------------------------- #
# processor integration — driven through the public `process()` dispatch (not the
# private per-message handlers); asserts on the payload the processor forwards to
# the REST client, which is where truncation must happen (right before the BE send).
#
# NOTE: truncation lives in OpikMessageProcessor, so these tests must drive it
# directly. The `fake_backend` fixture swaps in BackendEmulatorMessageProcessor,
# which bypasses OpikMessageProcessor entirely and would never truncate - so the
# public Opik.trace()/span() path can't exercise this feature. `_sent()` centralizes
# the one mock-internals access so a REST-wiring refactor only touches this helper.
# --------------------------------------------------------------------------- #


def _processor(max_payload_size_mb):
    return online_message_processor.OpikMessageProcessor(
        rest_client=mock.MagicMock(),
        file_upload_manager=mock.MagicMock(),
        fallback_replay_manager=mock.MagicMock(),
        unauthorized_message_types_registry=mock.MagicMock(),
        max_payload_size_mb=max_payload_size_mb,
    )


def _sent(processor, resource: str, method: str) -> dict:
    """The kwargs the processor forwarded to ``rest_client.<resource>.<method>``."""
    client_method = getattr(getattr(processor._rest_client, resource), method)
    return client_method.call_args.kwargs


def _create_span_message(**fields) -> messages.CreateSpanMessage:
    defaults = dict(
        span_id="span-id",
        trace_id="trace-id",
        project_name="my-project",
        parent_span_id=None,
        name="my-span",
        start_time=dt.datetime.now(tz=dt.timezone.utc),
        end_time=None,
        input=None,
        output=None,
        metadata=None,
        tags=None,
        type="general",
        usage=None,
        model=None,
        provider=None,
        error_info=None,
        total_cost=None,
        last_updated_at=None,
        source="sdk",
    )
    defaults.update(fields)
    return messages.CreateSpanMessage(**defaults)


def test_process_create_spans_batch__oversized_span_truncated_before_send():
    processor = _processor(max_payload_size_mb=LIMIT_MB)
    big_span = _span_write(output=_big_value(1.5))
    small_span = _span_write(output={"result": "small"})
    message = messages.CreateSpansBatchMessage(batch=[big_span, small_span])

    processor.process(message)

    sent = _sent(processor, "spans", "create_spans")["spans"]
    assert sent[0].output["opik_truncated"] is True  # oversized span truncated
    assert sent[1].output == {"result": "small"}  # small span passed through


def test_process_create_spans_batch__limit_disabled__no_truncation():
    processor = _processor(max_payload_size_mb=None)
    big_span = _span_write(output=_big_value(1.5))
    message = messages.CreateSpansBatchMessage(batch=[big_span])

    processor.process(message)

    sent = _sent(processor, "spans", "create_spans")["spans"]
    assert sent[0].output == big_span.output  # unchanged when disabled


def test_process_create_span__oversized_output_truncated_and_sibling_kept():
    # The specific oversized field must be the one truncated; the small sibling stays intact.
    processor = _processor(max_payload_size_mb=LIMIT_MB)
    message = _create_span_message(output=_big_value(1.5), input={"prompt": "small"})

    processor.process(message)

    sent_kwargs = _sent(processor, "spans", "create_span")
    assert sent_kwargs["output"]["opik_truncated"] is True  # the oversized field
    assert sent_kwargs["input"] == {"prompt": "small"}  # small sibling untouched


def test_process_create_span__limit_zero_disables():
    # A config value of 0 (<= 0) reaches the processor as a non-None int and disables
    # truncation via the payload_truncation guard - the whole config-driven disable path.
    processor = _processor(max_payload_size_mb=0)
    message = _create_span_message(output=_big_value(1.5))

    processor.process(message)

    sent_kwargs = _sent(processor, "spans", "create_span")
    assert sent_kwargs["output"] == _big_value(1.5)  # unchanged when disabled


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
    processor = _processor(max_payload_size_mb=LIMIT_MB)
    message = _update_span_message(output=_big_value(1.5), input={"prompt": "small"})

    processor.process(message)

    sent_kwargs = _sent(processor, "spans", "update_span")
    assert sent_kwargs["output"]["opik_truncated"] is True
    assert sent_kwargs["input"] == {"prompt": "small"}


def test_process_update_span__limit_disabled__no_truncation():
    processor = _processor(max_payload_size_mb=None)
    message = _update_span_message(output=_big_value(1.5))

    processor.process(message)

    sent_kwargs = _sent(processor, "spans", "update_span")
    assert sent_kwargs["output"] == _big_value(1.5)  # unchanged when disabled


# --------------------------------------------------------------------------- #
# TRACE paths — @track mirrors the payload onto the trace (a root span duplicating
# the trace data), so traces must be capped by the same per-object limit too.
# --------------------------------------------------------------------------- #


def _trace_write(**fields) -> trace_write.TraceWrite:
    return trace_write.TraceWrite(
        id="trace-id",
        name="my-trace",
        start_time=dt.datetime.now(tz=dt.timezone.utc),
        **fields,
    )


def _create_trace_message(**fields) -> messages.CreateTraceMessage:
    defaults = dict(
        trace_id="trace-id",
        project_name="my-project",
        name="my-trace",
        start_time=dt.datetime.now(tz=dt.timezone.utc),
        end_time=None,
        input=None,
        output=None,
        metadata=None,
        tags=None,
        error_info=None,
        thread_id=None,
        last_updated_at=None,
        source="sdk",
    )
    defaults.update(fields)
    return messages.CreateTraceMessage(**defaults)


def _update_trace_message(**fields) -> messages.UpdateTraceMessage:
    defaults = dict(
        trace_id="trace-id",
        project_name="my-project",
        end_time=None,
        input=None,
        output=None,
        metadata=None,
        tags=None,
        error_info=None,
        thread_id=None,
        source="sdk",
    )
    defaults.update(fields)
    return messages.UpdateTraceMessage(**defaults)


def test_process_create_trace__oversized_output_truncated_before_send():
    processor = _processor(max_payload_size_mb=LIMIT_MB)
    message = _create_trace_message(output=_big_value(1.5), input={"prompt": "small"})

    processor.process(message)

    sent = _sent(processor, "traces", "create_trace")
    assert sent["output"]["opik_truncated"] is True  # trace output truncated too
    assert sent["input"] == {"prompt": "small"}


def test_process_create_trace__metadata_not_truncated_and_small_sibling_kept():
    processor = _processor(max_payload_size_mb=LIMIT_MB)
    message = _create_trace_message(
        output=_big_value(1.5),
        input={"prompt": "small"},
        metadata={"thread_id": "t-1", "model": "gpt-4"},
    )

    processor.process(message)

    sent = _sent(processor, "traces", "create_trace")
    assert sent["output"]["opik_truncated"] is True  # only the oversized field
    assert sent["metadata"] == {"thread_id": "t-1", "model": "gpt-4"}  # metadata kept
    assert sent["input"] == {"prompt": "small"}  # small sibling not dropped/truncated


def test_process_update_trace__oversized_output_truncated_before_send():
    processor = _processor(max_payload_size_mb=LIMIT_MB)
    message = _update_trace_message(output=_big_value(1.5), input={"prompt": "small"})

    processor.process(message)

    sent = _sent(processor, "traces", "update_trace")
    assert sent["output"]["opik_truncated"] is True
    assert sent["input"] == {"prompt": "small"}


def test_process_create_traces_batch__oversized_trace_truncated():
    processor = _processor(max_payload_size_mb=LIMIT_MB)
    big_trace = _trace_write(output=_big_value(1.5))
    small_trace = _trace_write(output={"result": "small"})
    message = messages.CreateTraceBatchMessage(batch=[big_trace, small_trace])

    processor.process(message)

    sent = _sent(processor, "traces", "create_traces")["traces"]
    assert sent[0].output["opik_truncated"] is True
    assert sent[1].output == {"result": "small"}


def test_process_create_trace__limit_disabled__no_truncation():
    processor = _processor(max_payload_size_mb=None)
    message = _create_trace_message(output=_big_value(1.5))

    processor.process(message)

    sent = _sent(processor, "traces", "create_trace")
    assert sent["output"] == _big_value(1.5)  # unchanged when disabled
