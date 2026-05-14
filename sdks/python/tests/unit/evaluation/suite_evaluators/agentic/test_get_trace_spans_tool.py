import datetime
import json

from opik.message_processing.emulation import (
    local_emulator_message_processor,
    models,
)

from opik.evaluation.suite_evaluators.agentic.context import TraceToolContext
from opik.evaluation.suite_evaluators.agentic.tools.get_trace_spans import (
    GetTraceSpansTool,
)


def _trace_with_spans():
    start = datetime.datetime(2026, 5, 13, 12, 0, 0)
    trace = models.TraceModel(
        id="t-1",
        start_time=start,
        name="t",
        project_name="default",
        source="sdk",
        input="hi",
        output="bye",
        end_time=start + datetime.timedelta(seconds=2),
    )
    root = models.SpanModel(
        id="root",
        start_time=start,
        source="sdk",
        name="root",
        type="llm",
    )
    child = models.SpanModel(
        id="child",
        start_time=start + datetime.timedelta(seconds=1),
        source="sdk",
        name="tool_call",
        type="tool",
    )
    emulator = local_emulator_message_processor.LocalEmulatorMessageProcessor(
        active=True
    )
    return trace, [root, child], emulator


def test_get_trace_spans_returns_overview_json():
    trace, spans, emulator = _trace_with_spans()
    ctx = TraceToolContext(
        trace=trace,
        spans=spans,
        parent_by_child={"root": None, "child": "root"},
        emulator=emulator,
    )

    tool = GetTraceSpansTool()
    raw = tool.execute(arguments="{}", ctx=ctx)
    payload = json.loads(raw)

    assert payload["trace"]["id"] == "t-1"
    assert payload["trace"]["span_count"] == 2
    span_names = [s["name"] for s in payload["spans"]]
    assert span_names == ["root", "tool_call"]


def test_spec_advertises_no_required_parameters():
    spec = GetTraceSpansTool().spec
    assert spec["function"]["name"] == "get_trace_spans"
    assert spec["function"]["parameters"]["properties"] == {}
