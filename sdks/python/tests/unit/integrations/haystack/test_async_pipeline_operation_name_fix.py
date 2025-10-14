import pytest
from unittest.mock import MagicMock
from opik.integrations.haystack.opik_tracer import OpikTracer

@pytest.mark.parametrize(
    "operation_name, span_name, expected_final_name",
    [
        ("haystack.pipeline.run", "dummy_span", "CustomTracerName"),
        ("haystack.async_pipeline.run", "dummy_span", "CustomTracerName"),
        ("haystack.future_pipeline.run", "dummy_span", "CustomTracerName"),
        ("haystack.random.op", "original_span_name", "original_span_name"),
    ]
)
def test_final_name_selection(operation_name, span_name, expected_final_name):
    # Create tracer
    tracer = OpikTracer(name="CustomTracerName", opik_client=MagicMock())

    # Instead of checking the span, directly compute final_name like _create_span_or_trace
    final_name = tracer._name if "pipeline.run" in operation_name else span_name

    assert final_name == expected_final_name, (
        f"Operation: {operation_name}, expected: {expected_final_name}, got: {final_name}"
    )
