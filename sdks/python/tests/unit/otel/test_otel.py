from unittest.mock import MagicMock

from opik.otel.helpers import (
    extract_opik_distributed_trace_attributes,
    attach_to_parent,
)
from opik.otel.types import OpikDistributedTraceAttributes


class TestOpikDistributedTraceAttributes:
    def test_as_attributes__with_trace_id_and_parent_span_id__returns_both(self):
        attrs = OpikDistributedTraceAttributes(
            opik_trace_id="trace-123",
            opik_parent_span_id="span-456",
        )
        result = attrs.as_attributes()

        assert result == {
            "opik.trace_id": "trace-123",
            "opik.parent_span_id": "span-456",
        }

    def test_as_attributes__with_trace_id_only__returns_trace_id_only(self):
        attrs = OpikDistributedTraceAttributes(
            opik_trace_id="trace-123",
            opik_parent_span_id=None,
        )
        result = attrs.as_attributes()

        assert result == {"opik.trace_id": "trace-123"}
        assert "opik.parent_span_id" not in result


class TestExtractOpikDistributedTraceAttributes:
    def test_extract__headers_with_trace_id_and_parent_span_id__returns_attributes(
        self,
    ):
        headers = {
            "opik_trace_id": "trace-abc",
            "opik_parent_span_id": "span-def",
        }

        result = extract_opik_distributed_trace_attributes(headers)

        assert result is not None
        assert result.as_attributes() == {
            "opik.trace_id": "trace-abc",
            "opik.parent_span_id": "span-def",
        }

    def test_extract__headers_with_trace_id_only__returns_attributes_without_parent_span_id(
        self,
    ):
        headers = {"opik_trace_id": "trace-abc"}

        result = extract_opik_distributed_trace_attributes(headers)

        assert result is not None
        assert result.as_attributes() == {"opik.trace_id": "trace-abc"}

    def test_extract__headers_without_trace_id__returns_none(self):
        headers = {"opik_parent_span_id": "span-def", "other_header": "value"}

        result = extract_opik_distributed_trace_attributes(headers)

        assert result is None

    def test_extract__empty_headers__returns_none(self):
        result = extract_opik_distributed_trace_attributes({})

        assert result is None

    def test_extract__headers_with_extra_keys__ignores_extra_keys(self):
        headers = {
            "opik_trace_id": "trace-abc",
            "opik_parent_span_id": "span-def",
            "authorization": "Bearer token",
            "content-type": "application/json",
        }

        result = extract_opik_distributed_trace_attributes(headers)

        assert result is not None
        assert result.as_attributes() == {
            "opik.trace_id": "trace-abc",
            "opik.parent_span_id": "span-def",
        }


class TestAttachToParent:
    def test_attach_to_parent__valid_headers__sets_attributes_and_returns_true(self):
        span = MagicMock()
        headers = {
            "opik_trace_id": "trace-abc",
            "opik_parent_span_id": "span-def",
        }

        result = attach_to_parent(span, headers)

        assert result is True
        span.set_attributes.assert_called_once_with(
            {
                "opik.trace_id": "trace-abc",
                "opik.parent_span_id": "span-def",
            }
        )

    def test_attach_to_parent__trace_id_only__sets_trace_id_attribute_and_returns_true(
        self,
    ):
        span = MagicMock()
        headers = {"opik_trace_id": "trace-abc"}

        result = attach_to_parent(span, headers)

        assert result is True
        span.set_attributes.assert_called_once_with({"opik.trace_id": "trace-abc"})

    def test_attach_to_parent__missing_trace_id__returns_false_and_does_not_set_attributes(
        self,
    ):
        span = MagicMock()
        headers = {"opik_parent_span_id": "span-def"}

        result = attach_to_parent(span, headers)

        assert result is False
        span.set_attributes.assert_not_called()

    def test_attach_to_parent__empty_headers__returns_false(self):
        span = MagicMock()

        result = attach_to_parent(span, {})

        assert result is False
        span.set_attributes.assert_not_called()
