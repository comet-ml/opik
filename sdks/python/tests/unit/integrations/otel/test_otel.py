from unittest.mock import MagicMock, patch

from opik import id_helpers
from opik.integrations.otel import distributed_trace
from opik.integrations.otel import types as otel_types
from opik.integrations.otel.attributes import OPIK_SPAN_ID


TRACE_ID = "0193b3a5-1234-7abc-9def-0123456789ab"
PARENT_SPAN_ID = "0193b3a5-5678-7abc-9def-0123456789cd"


class TestOpikDistributedTraceAttributes:
    def test_as_attributes__with_trace_id_and_parent_span_id__returns_both(self):
        attrs = otel_types.OpikDistributedTraceAttributes(
            opik_trace_id="trace-123",
            opik_parent_span_id="span-456",
        )
        result = attrs.as_attributes()

        assert result == {
            "opik.trace_id": "trace-123",
            "opik.parent_span_id": "span-456",
        }

    def test_as_attributes__with_trace_id_only__returns_trace_id_only(self):
        attrs = otel_types.OpikDistributedTraceAttributes(
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
            "opik_trace_id": TRACE_ID,
            "opik_parent_span_id": PARENT_SPAN_ID,
        }

        with patch.object(distributed_trace.LOGGER, "warning") as warn_mock:
            result = distributed_trace.extract_opik_distributed_trace_attributes(
                headers
            )

        assert result is not None
        assert result.as_attributes() == {
            "opik.trace_id": TRACE_ID,
            "opik.parent_span_id": PARENT_SPAN_ID,
        }
        warn_mock.assert_not_called()

    def test_extract__headers_with_trace_id_only__returns_attributes_without_parent_span_id(
        self,
    ):
        headers = {"opik_trace_id": TRACE_ID}

        result = distributed_trace.extract_opik_distributed_trace_attributes(headers)

        assert result is not None
        assert result.as_attributes() == {"opik.trace_id": TRACE_ID}

    def test_extract__missing_trace_id_with_parent_span_id__returns_none_and_warns(
        self,
    ):
        headers = {"opik_parent_span_id": PARENT_SPAN_ID, "other_header": "value"}

        with patch.object(distributed_trace.LOGGER, "warning") as warn_mock:
            result = distributed_trace.extract_opik_distributed_trace_attributes(
                headers
            )

        assert result is None
        warn_mock.assert_called_once()
        assert "opik_trace_id" in warn_mock.call_args.args
        assert "opik_parent_span_id" in warn_mock.call_args.args

    def test_extract__missing_trace_id_without_parent_span_id__returns_none_silently(
        self,
    ):
        headers = {"other_header": "value"}

        with patch.object(distributed_trace.LOGGER, "warning") as warn_mock:
            result = distributed_trace.extract_opik_distributed_trace_attributes(
                headers
            )

        assert result is None
        warn_mock.assert_not_called()

    def test_extract__blank_trace_id_with_parent_span_id__returns_none_and_warns(self):
        headers = {
            "opik_trace_id": "   ",
            "opik_parent_span_id": PARENT_SPAN_ID,
        }

        with patch.object(distributed_trace.LOGGER, "warning") as warn_mock:
            result = distributed_trace.extract_opik_distributed_trace_attributes(
                headers
            )

        assert result is None
        warn_mock.assert_called_once()
        assert "opik_trace_id" in warn_mock.call_args.args

    def test_extract__empty_headers__returns_none(self):
        result = distributed_trace.extract_opik_distributed_trace_attributes({})

        assert result is None

    def test_extract__headers_with_extra_keys__ignores_extra_keys(self):
        headers = {
            "opik_trace_id": TRACE_ID,
            "opik_parent_span_id": PARENT_SPAN_ID,
            "authorization": "Bearer token",
            "content-type": "application/json",
        }

        result = distributed_trace.extract_opik_distributed_trace_attributes(headers)

        assert result is not None
        assert result.as_attributes() == {
            "opik.trace_id": TRACE_ID,
            "opik.parent_span_id": PARENT_SPAN_ID,
        }

    def test_extract__case_insensitive_header_names__matches(self):
        headers = {
            "OPIK_TRACE_ID": TRACE_ID,
            "Opik_Parent_Span_Id": PARENT_SPAN_ID,
        }

        result = distributed_trace.extract_opik_distributed_trace_attributes(headers)

        assert result is not None
        assert result.as_attributes() == {
            "opik.trace_id": TRACE_ID,
            "opik.parent_span_id": PARENT_SPAN_ID,
        }

    def test_extract__values_with_surrounding_whitespace__are_trimmed(self):
        headers = {
            "opik_trace_id": f"  {TRACE_ID}  ",
            "opik_parent_span_id": f"\t{PARENT_SPAN_ID}\n",
        }

        result = distributed_trace.extract_opik_distributed_trace_attributes(headers)

        assert result is not None
        assert result.as_attributes() == {
            "opik.trace_id": TRACE_ID,
            "opik.parent_span_id": PARENT_SPAN_ID,
        }

    def test_extract__empty_trace_id__returns_none(self):
        headers = {"opik_trace_id": ""}

        assert (
            distributed_trace.extract_opik_distributed_trace_attributes(headers) is None
        )

    def test_extract__whitespace_only_trace_id__returns_none(self):
        headers = {"opik_trace_id": "   \t\n"}

        assert (
            distributed_trace.extract_opik_distributed_trace_attributes(headers) is None
        )

    def test_extract__whitespace_only_parent_span_id__treated_as_absent(self):
        headers = {"opik_trace_id": TRACE_ID, "opik_parent_span_id": "   "}

        result = distributed_trace.extract_opik_distributed_trace_attributes(headers)

        assert result is not None
        assert result.as_attributes() == {"opik.trace_id": TRACE_ID}

    def test_extract__non_uuid_trace_id__returns_none_and_warns(self):
        headers = {"opik_trace_id": "not-a-uuid"}

        with patch.object(distributed_trace.LOGGER, "warning") as warn_mock:
            result = distributed_trace.extract_opik_distributed_trace_attributes(
                headers
            )

        assert result is None
        warn_mock.assert_called_once()
        assert "opik_trace_id" in warn_mock.call_args.args[1]

    def test_extract__non_uuid_parent_span_id__drops_parent_with_warning(self):
        headers = {
            "opik_trace_id": TRACE_ID,
            "opik_parent_span_id": "not-a-uuid",
        }

        with patch.object(distributed_trace.LOGGER, "warning") as warn_mock:
            result = distributed_trace.extract_opik_distributed_trace_attributes(
                headers
            )

        assert result is not None
        assert result.as_attributes() == {"opik.trace_id": TRACE_ID}
        warn_mock.assert_called_once()
        assert "opik_parent_span_id" in warn_mock.call_args.args[1]

    def test_extract__both_invalid__warns_only_for_trace_id(self):
        headers = {
            "opik_trace_id": "garbage",
            "opik_parent_span_id": "also-garbage",
        }

        with patch.object(distributed_trace.LOGGER, "warning") as warn_mock:
            result = distributed_trace.extract_opik_distributed_trace_attributes(
                headers
            )

        assert result is None
        warn_mock.assert_called_once()
        assert "opik_trace_id" in warn_mock.call_args.args[1]


class TestAttachToParent:
    def test_attach_to_parent__valid_headers__sets_attributes_and_returns_true(self):
        span = MagicMock()
        headers = {
            "opik_trace_id": TRACE_ID,
            "opik_parent_span_id": PARENT_SPAN_ID,
        }

        result = distributed_trace.attach_to_parent(span, headers)

        assert result is True
        span.set_attributes.assert_called_once()
        attrs = span.set_attributes.call_args.args[0]
        assert attrs["opik.trace_id"] == TRACE_ID
        assert attrs["opik.parent_span_id"] == PARENT_SPAN_ID
        # boundary span gets a freshly minted UUIDv7 to chain descendants through
        assert id_helpers.is_valid_uuid_v7(attrs[OPIK_SPAN_ID])

    def test_attach_to_parent__trace_id_only__sets_trace_id_attribute_and_returns_true(
        self,
    ):
        span = MagicMock()
        headers = {"opik_trace_id": TRACE_ID}

        result = distributed_trace.attach_to_parent(span, headers)

        assert result is True
        span.set_attributes.assert_called_once()
        attrs = span.set_attributes.call_args.args[0]
        assert attrs == {
            "opik.trace_id": TRACE_ID,
            OPIK_SPAN_ID: attrs[OPIK_SPAN_ID],
        }
        assert id_helpers.is_valid_uuid_v7(attrs[OPIK_SPAN_ID])

    def test_attach_to_parent__missing_trace_id_with_parent_span_id__returns_false_and_warns(
        self,
    ):
        span = MagicMock()
        headers = {"opik_parent_span_id": PARENT_SPAN_ID}

        with patch.object(distributed_trace.LOGGER, "warning") as warn_mock:
            result = distributed_trace.attach_to_parent(span, headers)

        assert result is False
        span.set_attributes.assert_not_called()
        warn_mock.assert_called_once()

    def test_attach_to_parent__empty_headers__returns_false(self):
        span = MagicMock()

        result = distributed_trace.attach_to_parent(span, {})

        assert result is False
        span.set_attributes.assert_not_called()

    def test_attach_to_parent__non_uuid_trace_id__returns_false_and_warns(self):
        span = MagicMock()

        with patch.object(distributed_trace.LOGGER, "warning") as warn_mock:
            result = distributed_trace.attach_to_parent(
                span, {"opik_trace_id": "not-a-uuid"}
            )

        assert result is False
        span.set_attributes.assert_not_called()
        warn_mock.assert_called_once()

    def test_attach_to_parent__non_uuid_parent_span_id__attaches_trace_id_only_with_warning(
        self,
    ):
        span = MagicMock()
        headers = {
            "opik_trace_id": TRACE_ID,
            "opik_parent_span_id": "not-a-uuid",
        }

        with patch.object(distributed_trace.LOGGER, "warning") as warn_mock:
            result = distributed_trace.attach_to_parent(span, headers)

        assert result is True
        attrs = span.set_attributes.call_args.args[0]
        assert attrs["opik.trace_id"] == TRACE_ID
        assert "opik.parent_span_id" not in attrs
        assert id_helpers.is_valid_uuid_v7(attrs[OPIK_SPAN_ID])
        warn_mock.assert_called_once()
