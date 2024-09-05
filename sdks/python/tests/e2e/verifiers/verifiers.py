from typing import Optional, Dict, Any, List
from opik.rest_api import client as rest_api_client
from opik import synchronization
import mock


def verify_trace(
    rest_client: rest_api_client.OpikApi,
    trace_id: str,
    name: str = mock.ANY,  # type: ignore
    metadata: Dict[str, Any] = mock.ANY,  # type: ignore
    input: Dict[str, Any] = mock.ANY,  # type: ignore
    output: Dict[str, Any] = mock.ANY,  # type: ignore
    tags: List[str] = mock.ANY,  # type: ignore
):
    if not synchronization.until(
        lambda: (rest_client.traces.get_trace_by_id(id=trace_id) is not None),
        allow_errors=True,
    ):
        raise AssertionError(f"Failed to get trace with id {trace_id}.")

    trace = rest_client.traces.get_trace_by_id(id=trace_id)

    assert trace.name == name, f"{trace.name} != {name}"
    assert trace.input == input, f"{trace.input} != {input}"
    assert trace.output == output, f"{trace.output} != {output}"
    assert trace.tags == tags, f"{trace.tags} != {tags}"
    assert trace.metadata == metadata, f"{trace.metadata} != {metadata}"


def verify_span(
    rest_client: rest_api_client.OpikApi,
    span_id: str,
    trace_id: str,
    parent_span_id: Optional[str],
    name: str = mock.ANY,  # type: ignore
    metadata: Dict[str, Any] = mock.ANY,  # type: ignore
    input: Dict[str, Any] = mock.ANY,  # type: ignore
    output: Dict[str, Any] = mock.ANY,  # type: ignore
    tags: List[str] = mock.ANY,  # type: ignore
    type: str = mock.ANY,  # type: ignore
):
    if not synchronization.until(
        lambda: (rest_client.spans.get_span_by_id(id=span_id) is not None),
        allow_errors=True,
    ):
        raise AssertionError(f"Failed to get span with id {id}.")

    span = rest_client.spans.get_span_by_id(id=span_id)

    assert span.trace_id == trace_id, f"{span.trace_id} != {trace_id}"

    if parent_span_id is None:
        assert span.parent_span_id is None, f"{span.parent_span_id} != {parent_span_id}"
    else:
        assert (
            span.parent_span_id == parent_span_id
        ), f"{span.parent_span_id} != {parent_span_id}"

    assert span.name == name, f"{span.name} != {name}"
    assert span.input == input, f"{span.input} != {input}"
    assert span.output == output, f"{span.output} != {output}"
    assert span.tags == tags, f"{span.tags} != {tags}"
    assert span.metadata == metadata, f"{span.metadata} != {metadata}"
    assert span.type == type, f"{span.type} != {type}"
