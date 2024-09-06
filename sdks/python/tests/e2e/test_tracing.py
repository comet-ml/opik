from . import verifiers
import opik


from opik import opik_context


def test_tracked_function__happyflow(opik_client):
    # Setup
    ID_STORAGE = {}

    @opik.track(
        tags=["outer-tag1", "outer-tag2"],
        metadata={"outer-metadata-key": "outer-metadata-value"},
    )
    def f_outer(x):
        ID_STORAGE["f_outer-trace-id"] = opik_context.get_current_trace().id
        ID_STORAGE["f_outer-span-id"] = opik_context.get_current_span().id

        f_inner("inner-input")
        return "outer-output"

    @opik.track(
        tags=["inner-tag1", "inner-tag2"],
        metadata={"inner-metadata-key": "inner-metadata-value"},
    )
    def f_inner(y):
        ID_STORAGE["f_inner-span-id"] = opik_context.get_current_span().id
        return "inner-output"

    # Call
    f_outer("outer-input")
    opik.flush_tracker()

    # Verify trace
    verifiers.verify_trace(
        opik_client=opik_client,
        trace_id=ID_STORAGE["f_outer-trace-id"],
        name="f_outer",
        input={"x": "outer-input"},
        output={"output": "outer-output"},
        metadata={"outer-metadata-key": "outer-metadata-value"},
        tags=["outer-tag1", "outer-tag2"],
    )

    # Verify top level span
    verifiers.verify_span(
        opik_client=opik_client,
        span_id=ID_STORAGE["f_outer-span-id"],
        parent_span_id=None,
        trace_id=ID_STORAGE["f_outer-trace-id"],
        name="f_outer",
        input={"x": "outer-input"},
        output={"output": "outer-output"},
        metadata={"outer-metadata-key": "outer-metadata-value"},
        tags=["outer-tag1", "outer-tag2"],
    )

    # Verify nested span
    verifiers.verify_span(
        opik_client=opik_client,
        span_id=ID_STORAGE["f_inner-span-id"],
        parent_span_id=ID_STORAGE["f_outer-span-id"],
        trace_id=ID_STORAGE["f_outer-trace-id"],
        name="f_inner",
        input={"y": "inner-input"},
        output={"output": "inner-output"},
        metadata={"inner-metadata-key": "inner-metadata-value"},
        tags=["inner-tag1", "inner-tag2"],
    )


def test_manually_created_trace_and_span__happyflow(opik_client: opik.Opik):
    # Call
    trace = opik_client.trace(
        name="trace-name",
        input={"input": "trace-input"},
        output={"output": "trace-output"},
        tags=["trace-tag"],
        metadata={"trace-metadata-key": "trace-metadata-value"},
    )
    span = trace.span(
        name="span-name",
        input={"input": "span-input"},
        output={"output": "span-output"},
        tags=["span-tag"],
        metadata={"span-metadata-key": "span-metadata-value"},
    )

    opik_client.flush()

    # Verify trace
    verifiers.verify_trace(
        opik_client=opik_client,
        trace_id=trace.id,
        name="trace-name",
        input={"input": "trace-input"},
        output={"output": "trace-output"},
        tags=["trace-tag"],
        metadata={"trace-metadata-key": "trace-metadata-value"},
    )

    # Verify span
    verifiers.verify_span(
        opik_client=opik_client,
        span_id=span.id,
        parent_span_id=None,
        trace_id=span.trace_id,
        name="span-name",
        input={"input": "span-input"},
        output={"output": "span-output"},
        tags=["span-tag"],
        metadata={"span-metadata-key": "span-metadata-value"},
    )
