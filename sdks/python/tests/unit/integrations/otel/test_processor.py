import asyncio
import threading

import pytest
from opentelemetry import baggage, context as context_api
from opentelemetry.sdk.trace import TracerProvider
from opentelemetry.sdk.trace.export import SimpleSpanProcessor
from opentelemetry.sdk.trace.export.in_memory_span_exporter import (
    InMemorySpanExporter,
)

import opik
from opik import id_helpers, opik_context
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


@pytest.fixture
def inmemory_provider():
    """Provider that captures finished spans in memory, with OpikSpanProcessor
    registered. Teardown via yield so shutdown runs even if a test fails."""
    exporter = InMemorySpanExporter()
    provider = TracerProvider()
    provider.add_span_processor(SimpleSpanProcessor(exporter))
    provider.add_span_processor(otel.OpikSpanProcessor())
    yield provider, exporter
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


class TestInProcessOpikTrackContextFallback:
    """When no parent span / baggage carries Opik IDs, the processor attaches the
    OTel subtree to the active ``@opik.track`` span. This is what links libraries
    that emit their own OTel spans (logfire / PydanticAI) to a tracked function.
    """

    def test_on_start__no_active_opik_context__span_untouched(self, tracer):
        with tracer.start_as_current_span("standalone") as span:
            attrs = dict(span.attributes or {})

        assert otel_attributes.OPIK_TRACE_ID not in attrs
        assert otel_attributes.OPIK_SPAN_ID not in attrs
        assert otel_attributes.OPIK_PARENT_SPAN_ID not in attrs

    def test_on_start__inside_opik_track_context__attaches_to_tracked_span(
        self, tracer, fake_backend
    ):
        captured = {}

        @opik.track(name="tracked")
        def handler():
            headers = opik_context.get_distributed_trace_headers()
            captured["trace_id"] = headers["opik_trace_id"]
            captured["span_id"] = headers["opik_parent_span_id"]
            with tracer.start_as_current_span("otel-root") as span:
                captured["attrs"] = dict(span.attributes or {})

        handler()
        opik.flush_tracker()

        assert captured["attrs"][otel_attributes.OPIK_TRACE_ID] == captured["trace_id"]
        assert (
            captured["attrs"][otel_attributes.OPIK_PARENT_SPAN_ID]
            == captured["span_id"]
        )
        # a fresh UUIDv7 is minted for the OTel span itself
        assert id_helpers.is_valid_uuid_v7(
            captured["attrs"][otel_attributes.OPIK_SPAN_ID]
        )
        assert captured["attrs"][otel_attributes.OPIK_SPAN_ID] != captured["span_id"]

    def test_on_start__inside_opik_track_context__descendants_chain_through_root(
        self, tracer, fake_backend
    ):
        captured = {}

        @opik.track(name="tracked")
        def handler():
            captured["span_id"] = opik_context.get_current_span_data().id
            with tracer.start_as_current_span("otel-root") as root:
                captured["root"] = dict(root.attributes or {})
                with tracer.start_as_current_span("otel-child") as child:
                    captured["child"] = dict(child.attributes or {})

        handler()
        opik.flush_tracker()

        # root attaches directly to the tracked span
        assert (
            captured["root"][otel_attributes.OPIK_PARENT_SPAN_ID] == captured["span_id"]
        )
        # child chains through the root's opik.span_id, not the tracked span
        assert (
            captured["child"][otel_attributes.OPIK_PARENT_SPAN_ID]
            == captured["root"][otel_attributes.OPIK_SPAN_ID]
        )
        # all share the tracked trace
        assert (
            captured["child"][otel_attributes.OPIK_TRACE_ID]
            == captured["root"][otel_attributes.OPIK_TRACE_ID]
        )

    def test_on_start__inside_async_opik_track_context__attaches_to_tracked_span(
        self, tracer, fake_backend
    ):
        captured = {}

        @opik.track(name="tracked-async")
        async def handler():
            headers = opik_context.get_distributed_trace_headers()
            captured["trace_id"] = headers["opik_trace_id"]
            captured["span_id"] = headers["opik_parent_span_id"]
            with tracer.start_as_current_span("otel-root") as span:
                captured["attrs"] = dict(span.attributes or {})

        asyncio.run(handler())
        opik.flush_tracker()

        assert captured["attrs"][otel_attributes.OPIK_TRACE_ID] == captured["trace_id"]
        assert (
            captured["attrs"][otel_attributes.OPIK_PARENT_SPAN_ID]
            == captured["span_id"]
        )

    def test_on_start__multiple_tracers_sibling_roots__all_link_to_tracked_span(
        self, inmemory_provider, fake_backend
    ):
        # Generic OTel: spans from independent tracers (i.e. any OTel-based
        # instrumentation, not just logfire) all attach to the tracked span.
        provider, exporter = inmemory_provider
        lib_a = provider.get_tracer("vendor.a")
        lib_b = provider.get_tracer("framework.b")
        captured = {}

        @opik.track(name="tracked")
        def handler():
            captured["trace_id"] = opik_context.get_current_trace_data().id
            captured["span_id"] = opik_context.get_current_span_data().id
            with lib_a.start_as_current_span("a-root"):
                pass
            with lib_b.start_as_current_span("b-root"):
                pass

        handler()
        opik.flush_tracker()

        spans = exporter.get_finished_spans()
        assert len(spans) == 2
        for span in spans:
            attrs = dict(span.attributes or {})
            assert attrs[otel_attributes.OPIK_TRACE_ID] == captured["trace_id"]
            # both are top-level OTel roots -> siblings directly under the tracked span
            assert attrs[otel_attributes.OPIK_PARENT_SPAN_ID] == captured["span_id"]

    def test_on_start__nested_opik_track__otel_attaches_to_innermost_span(
        self, tracer, fake_backend
    ):
        captured = {}

        @opik.track(name="inner")
        def inner():
            captured["inner_span_id"] = opik_context.get_current_span_data().id
            with tracer.start_as_current_span("otel") as span:
                captured["attrs"] = dict(span.attributes or {})

        @opik.track(name="outer")
        def outer():
            captured["outer_span_id"] = opik_context.get_current_span_data().id
            inner()

        outer()
        opik.flush_tracker()

        # OTel span attaches to the innermost active tracked span, not the outer one
        assert (
            captured["attrs"][otel_attributes.OPIK_PARENT_SPAN_ID]
            == captured["inner_span_id"]
        )
        assert (
            captured["attrs"][otel_attributes.OPIK_PARENT_SPAN_ID]
            != captured["outer_span_id"]
        )

    def test_on_start__baggage_present_inside_track_context__baggage_takes_precedence(
        self, tracer, fake_backend
    ):
        # A cross-process distributed context (baggage) represents an explicit
        # upstream parent and must win over the local @opik.track context, so the
        # in-process fallback never regresses the distributed-tracing flow.
        captured = {}
        ctx = baggage.set_baggage(otel_attributes.OPIK_TRACE_ID, TRACE_ID)
        ctx = baggage.set_baggage(
            otel_attributes.OPIK_SPAN_ID,
            "0193b3a5-cccc-7abc-9def-000000000003",
            context=ctx,
        )

        @opik.track(name="tracked")
        def handler():
            captured["track_trace_id"] = opik_context.get_current_trace_data().id
            token = context_api.attach(ctx)
            try:
                with tracer.start_as_current_span("span") as span:
                    captured["attrs"] = dict(span.attributes or {})
            finally:
                context_api.detach(token)

        handler()
        opik.flush_tracker()

        assert captured["attrs"][otel_attributes.OPIK_TRACE_ID] == TRACE_ID
        assert (
            captured["attrs"][otel_attributes.OPIK_TRACE_ID]
            != captured["track_trace_id"]
        )

    def test_on_start__invalid_baggage_inside_track_context__not_linked(
        self, tracer, fake_backend
    ):
        # A broken upstream distributed context must not be silently absorbed into
        # the local @opik.track trace — the span is left standalone, not linked.
        captured = {}
        ctx = baggage.set_baggage(otel_attributes.OPIK_TRACE_ID, "not-a-uuid")

        @opik.track(name="tracked")
        def handler():
            captured["track_trace_id"] = opik_context.get_current_trace_data().id
            token = context_api.attach(ctx)
            try:
                with tracer.start_as_current_span("span") as span:
                    captured["attrs"] = dict(span.attributes or {})
            finally:
                context_api.detach(token)

        handler()
        opik.flush_tracker()

        assert otel_attributes.OPIK_TRACE_ID not in captured["attrs"]
        assert otel_attributes.OPIK_PARENT_SPAN_ID not in captured["attrs"]

    def test_on_start__otel_span_started_in_worker_thread__not_linked(
        self, tracer, fake_backend
    ):
        # The fallback reads Opik's contextvars; a bare worker thread does not
        # inherit them. The span gracefully falls back to today's standalone
        # behavior instead of linking. Documents the boundary of the fallback.
        captured = {}

        @opik.track(name="tracked")
        def handler():
            def worker():
                with tracer.start_as_current_span("in-thread") as span:
                    captured["attrs"] = dict(span.attributes or {})

            thread = threading.Thread(target=worker)
            thread.start()
            thread.join()

        handler()
        opik.flush_tracker()

        assert otel_attributes.OPIK_TRACE_ID not in captured["attrs"]
        assert otel_attributes.OPIK_PARENT_SPAN_ID not in captured["attrs"]


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
