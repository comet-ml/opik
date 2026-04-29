import pytest
from opentelemetry import baggage, context as context_api
from opentelemetry.sdk.trace import TracerProvider

from opik import id_helpers
from opik.integrations import otel
from opik.integrations.otel import attributes as otel_attributes


TRACE_ID = "0193b3a5-1234-7abc-9def-0123456789ab"
PARENT_SPAN_ID = "0193b3a5-5678-7abc-9def-0123456789cd"


@pytest.fixture
def tracer():
    provider = TracerProvider()
    provider.add_span_processor(otel.OpikSpanProcessor())
    yield provider.get_tracer(__name__)
    provider.shutdown()


class TestOpikSpanProcessor:
    def test_on_start__parent_has_no_opik_attrs__leaves_span_and_child_untouched(
        self, tracer
    ):
        with tracer.start_as_current_span("parent") as span:
            parent_attrs = dict(span.attributes or {})
            with tracer.start_as_current_span("child") as child:
                child_attrs = dict(child.attributes or {})

        assert otel_attributes.OPIK_TRACE_ID not in parent_attrs
        assert otel_attributes.OPIK_SPAN_ID not in parent_attrs
        assert otel_attributes.OPIK_PARENT_SPAN_ID not in parent_attrs

        assert otel_attributes.OPIK_TRACE_ID not in child_attrs
        assert otel_attributes.OPIK_SPAN_ID not in child_attrs
        assert otel_attributes.OPIK_PARENT_SPAN_ID not in child_attrs

    def test_on_start__parent_has_full_triple__child_inherits_and_chains(self, tracer):
        with tracer.start_as_current_span("parent") as parent:
            # Simulate: parent already has the triple (e.g. propagated via attach_to_parent or
            # an earlier processor pass).
            parent.set_attribute(otel_attributes.OPIK_TRACE_ID, TRACE_ID)
            parent.set_attribute(
                otel_attributes.OPIK_SPAN_ID, "0193b3a5-aaaa-7abc-9def-000000000001"
            )
            with tracer.start_as_current_span("child") as child:
                child_attrs = dict(child.attributes)

        assert child_attrs[otel_attributes.OPIK_TRACE_ID] == TRACE_ID
        # child gets a freshly minted opik.span_id (UUIDv7, distinct from parent's)
        assert id_helpers.is_valid_uuid_v7(child_attrs[otel_attributes.OPIK_SPAN_ID])
        assert (
            child_attrs[otel_attributes.OPIK_SPAN_ID]
            != "0193b3a5-aaaa-7abc-9def-000000000001"
        )
        # child's opik.parent_span_id == parent's opik.span_id
        assert (
            child_attrs[otel_attributes.OPIK_PARENT_SPAN_ID]
            == "0193b3a5-aaaa-7abc-9def-000000000001"
        )

    def test_on_start__parent_has_trace_id_without_span_id__no_inheritance(
        self, tracer
    ):
        # If a parent carries opik.trace_id without opik.span_id, the processor cannot
        # chain a stable parent_span_id and refuses to guess. attach_to_parent always
        # sets both, so this state only arises from a misconfigured upstream.
        with tracer.start_as_current_span("parent") as parent:
            parent.set_attribute(otel_attributes.OPIK_TRACE_ID, TRACE_ID)
            parent.set_attribute(otel_attributes.OPIK_PARENT_SPAN_ID, PARENT_SPAN_ID)
            with tracer.start_as_current_span("child") as child:
                child_attrs = dict(child.attributes or {})

        assert otel_attributes.OPIK_TRACE_ID not in child_attrs
        assert otel_attributes.OPIK_SPAN_ID not in child_attrs
        assert otel_attributes.OPIK_PARENT_SPAN_ID not in child_attrs

    def test_on_start__baggage_carries_opik_ids__span_inherits_from_baggage(
        self, tracer
    ):
        ctx = baggage.set_baggage(otel_attributes.OPIK_TRACE_ID, TRACE_ID)
        ctx = baggage.set_baggage(
            otel_attributes.OPIK_SPAN_ID,
            "0193b3a5-bbbb-7abc-9def-000000000002",
            context=ctx,
        )
        token = context_api.attach(ctx)
        try:
            with tracer.start_as_current_span("from_baggage") as span:
                span_attrs = dict(span.attributes)
        finally:
            context_api.detach(token)

        assert span_attrs[otel_attributes.OPIK_TRACE_ID] == TRACE_ID
        assert id_helpers.is_valid_uuid_v7(span_attrs[otel_attributes.OPIK_SPAN_ID])
        assert (
            span_attrs[otel_attributes.OPIK_PARENT_SPAN_ID]
            == "0193b3a5-bbbb-7abc-9def-000000000002"
        )

    def test_on_start__baggage_only_trace_id__span_gets_trace_and_no_parent(
        self, tracer
    ):
        ctx = baggage.set_baggage(otel_attributes.OPIK_TRACE_ID, TRACE_ID)
        token = context_api.attach(ctx)
        try:
            with tracer.start_as_current_span("from_baggage") as span:
                span_attrs = dict(span.attributes)
        finally:
            context_api.detach(token)

        assert span_attrs[otel_attributes.OPIK_TRACE_ID] == TRACE_ID
        assert id_helpers.is_valid_uuid_v7(span_attrs[otel_attributes.OPIK_SPAN_ID])
        assert otel_attributes.OPIK_PARENT_SPAN_ID not in span_attrs


class TestAttachToParentWithProcessor:
    def test_attach_to_parent__deep_chain_keeps_each_level_linked(self, tracer):
        headers = {"opik_trace_id": TRACE_ID, "opik_parent_span_id": PARENT_SPAN_ID}
        with tracer.start_as_current_span("boundary") as boundary:
            ok = otel.attach_to_parent(boundary, headers)
            assert ok is True
            boundary_span_id = boundary.attributes[otel_attributes.OPIK_SPAN_ID]

            with tracer.start_as_current_span("level1") as l1:
                l1_attrs = dict(l1.attributes)
                with tracer.start_as_current_span("level2") as l2:
                    l2_attrs = dict(l2.attributes)

        # Each level should reference its immediate parent's opik.span_id.
        assert l1_attrs[otel_attributes.OPIK_PARENT_SPAN_ID] == boundary_span_id
        l1_span_id = l1_attrs[otel_attributes.OPIK_SPAN_ID]
        assert l2_attrs[otel_attributes.OPIK_PARENT_SPAN_ID] == l1_span_id

        # All share the same trace.
        assert l1_attrs[otel_attributes.OPIK_TRACE_ID] == TRACE_ID
        assert l2_attrs[otel_attributes.OPIK_TRACE_ID] == TRACE_ID

        # All is valid UUIDv7.
        assert id_helpers.is_valid_uuid_v7(boundary_span_id)
        assert id_helpers.is_valid_uuid_v7(l1_span_id)
        assert id_helpers.is_valid_uuid_v7(l2_attrs[otel_attributes.OPIK_SPAN_ID])
