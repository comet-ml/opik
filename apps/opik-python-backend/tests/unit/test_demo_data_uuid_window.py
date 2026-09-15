"""
End-to-end check that demo seeding produces zero UUIDv7 rejections under reject-mode validation.

`create_demo_data` posts to the same REST batch endpoints as any client
(POST /v1/private/{traces,spans}/batch), which is exactly the path
UuidV7TimestampValidator guards. Here the mock backend applies that validator's rule to every
id it receives and 400s the batch on the first out-of-window id, the way reject mode does.

The check is deployment-agnostic on purpose: the window used is the 12h configuration minimum
(UuidValidationConfig: @MinDuration(value = 12, unit = HOURS)), so passing here means the demo
seeds cleanly under *every* legal window setting on OSS Docker, Helm and Comet cloud alike.
"""
import datetime
import gzip
import json
import re
import uuid
import zlib

import pytest
import uuid6

from opik_backend.demo_data_generator import (
    DEMO_ID_MAX_AGE,
    create_demo_data,
    uuid7_from_datetime,
)
from opik_backend.demo_data import demo_spans, demo_traces

# The smallest window an operator can configure. Validating against it covers every larger one.
MIN_CONFIGURABLE_WINDOW = datetime.timedelta(hours=12)

# Marks a request body decode_payload could not read. Present in the returned dict instead of raising.
UNDECODABLE_BODY = "__undecodable__"


def embedded_timestamp(raw_id):
    """Read back the instant the backend derives from an id, per RetentionUtils.extractInstant.

    The top 48 bits are epoch milliseconds, and the validator reads them regardless of UUID
    version because those bits drive ClickHouse partition placement.
    """
    return datetime.datetime.fromtimestamp((uuid.UUID(raw_id).int >> 80) / 1000.0)


def decode_payload(request):
    """Read a batch request body, transparently un-gzipping it.

    The SDK's REST client compresses batch payloads, so the raw body is usually gzip rather than
    JSON. Anything unparseable must surface as a test failure rather than an exception out of the
    handler — a raising handler returns a 500, which the generator retries, and the run turns into
    a very long timeout instead of a clear assertion.
    """
    body = request.get_data()
    for decompress in (lambda raw: raw, gzip.decompress, zlib.decompress):
        try:
            return json.loads(decompress(body))
        except (OSError, zlib.error, UnicodeDecodeError, json.JSONDecodeError):
            continue
    # Deliberately not raising: a raising handler returns 500, which the generator retries, turning a
    # clear failure into a long timeout. The caller must therefore check for this key — an
    # undecodable body yields no items, and "no items" must not be mistaken for "nothing to reject".
    return {UNDECODABLE_BODY: body[:32]}


class UuidWindowValidator:
    """Mock ingestion endpoint enforcing the UUIDv7 window in reject mode.

    `reference_now` is the instant the window is measured from. Pass one, captured once by the
    caller: the real backend reads the clock per request, but reproducing that here would make the
    verdict depend on two independent clock reads — the generator's, when it compresses the timeline,
    and this validator's, per batch. The demo leaves ~2h of slack under the 12h floor, so only a
    sizeable forward jump (a resumed CI runner, an NTP correction) could turn a correct payload into
    `too_old`. That is a property of the clock, not of the payload this test exists to check, so it
    should not be able to fail the test. Omitting it falls back to per-request reads, which is what
    the standalone validator tests use to exercise the rejection logic itself.
    """

    def __init__(self, payload_key, resource, window=MIN_CONFIGURABLE_WINDOW,
                 reference_now=None):
        self.payload_key = payload_key
        self.resource = resource
        self.window = window
        self.reference_now = reference_now
        self.accepted = []
        self.rejections = []

    def __call__(self, request):
        from werkzeug.wrappers import Response

        now = self.reference_now or datetime.datetime.now()
        payload = decode_payload(request)

        # An unreadable body would otherwise yield zero items, zero rejections and a 204 — the mock
        # would report success for a request it never inspected. Fail it explicitly instead.
        if UNDECODABLE_BODY in payload:
            self.rejections.append(
                (self.resource, None, None, "undecodable_body"))
            return Response(
                json.dumps({"code": 400, "message": "could not decode request body"}),
                status=400, content_type="application/json")

        items = payload.get(self.payload_key, [])

        for item in items:
            # IdGenerator.validateVersion runs first and unconditionally: a non-v7 id is a 400
            # regardless of its timestamp. Without this the mock would accept an in-window v4 id
            # that production rejects, and the test would claim more than it proves.
            if uuid.UUID(item["id"]).version != 7:
                self.rejections.append(
                    (self.resource, item["id"], None, "not_version_7"))
                continue

            timestamp = embedded_timestamp(item["id"])
            if timestamp < now - self.window:
                self.rejections.append((self.resource, item["id"], timestamp, "too_old"))
            elif timestamp > now + self.window:
                self.rejections.append(
                    (self.resource, item["id"], timestamp, "too_far_future"))
            else:
                self.accepted.append(item)

        if self.rejections:
            # Mirror InvalidUUIDExceptionMapper: the whole batch fails with a 400.
            return Response(
                json.dumps({"code": 400, "message": "id outside the allowed ingestion window"}),
                status=400, content_type="application/json")
        return Response("", status=204)


def register_demo_mocks(httpserver, trace_handler=None, span_handler=None,
                        project_handler=None):
    """Register the backend endpoints demo seeding touches.

    Mirrors the happy-path mocks in test_demo_data_generator; the trace/span batch endpoints are
    overridable so a test can assert on the payloads that actually go over the wire.
    """
    httpserver.expect_request("/is-alive/ping", method="GET").respond_with_data("pong", status=200)
    httpserver.expect_request("/v1/private/projects/retrieve", method="POST").respond_with_data(status=404)
    httpserver.expect_request("/v1/private/projects", method="GET").respond_with_json(
        {"content": [], "page": 1, "size": 0, "total": 0})
    if project_handler:
        httpserver.expect_request("/v1/private/projects", method="POST").respond_with_handler(project_handler)
    else:
        httpserver.expect_request("/v1/private/projects", method="POST").respond_with_data(status=201)

    if trace_handler:
        httpserver.expect_request("/v1/private/traces/batch", method="POST").respond_with_handler(trace_handler)
    else:
        httpserver.expect_request("/v1/private/traces/batch", method="POST").respond_with_data(status=204)
    if span_handler:
        httpserver.expect_request("/v1/private/spans/batch", method="POST").respond_with_handler(span_handler)
    else:
        httpserver.expect_request("/v1/private/spans/batch", method="POST").respond_with_data(status=204)

    httpserver.expect_request("/v1/private/traces/feedback-scores", method="PUT").respond_with_data(status=204)
    httpserver.expect_request(
        "/v1/private/feedback-definitions", method="GET", query_string="name=User+feedback"
    ).respond_with_json({"content": [], "page": 1, "size": 0, "total": 0})
    httpserver.expect_request("/v1/private/feedback-definitions", method="POST").respond_with_data(status=201)
    httpserver.expect_request("/v1/private/environments", method="POST").respond_with_data(status=201)
    httpserver.expect_request("/v1/private/prompts", method="POST").respond_with_data(status=201)
    httpserver.expect_request("/v1/private/datasets", method="POST").respond_with_data(status=201)
    httpserver.expect_request("/v1/private/datasets/retrieve", method="POST").respond_with_json({
        "id": str(uuid6.uuid7()), "name": "Demo dataset", "description": "", "metadata": {},
        "created_at": "2024-01-01T00:00:00Z", "last_updated_at": "2024-01-01T00:00:00Z",
    })
    httpserver.expect_request("/v1/private/datasets/items", method="POST").respond_with_data(status=201)

    dataset_items = [
        {"data": {"input": "What is the best LLM evaluation tool?", "output": "Comet"},
         "id": str(uuid6.uuid7()), "source": "sdk"},
        {"data": {"input": "What is the easiest way to start with Opik?", "output": "Read the docs"},
         "id": str(uuid6.uuid7()), "source": "sdk"},
        {"data": {"input": "Is Opik open source?", "output": "Yes"},
         "id": str(uuid6.uuid7()), "source": "sdk"},
    ]
    httpserver.expect_request("v1/private/datasets/items/stream", method="POST").respond_with_data(
        status=200, headers={"Content-Type": "application/octet-stream"},
        response_data=b"\n".join(json.dumps(item).encode("utf-8") for item in dataset_items))
    httpserver.expect_request("/v1/private/datasets/items/stream", method="POST").respond_with_data(status=200)
    httpserver.expect_request("/v1/private/datasets/items", method="PUT").respond_with_data(status=204)
    httpserver.expect_request("/v1/private/experiments", method="POST").respond_with_data(status=201)
    httpserver.expect_request("/v1/private/experiments/items", method="POST").respond_with_data(status=204)

    prompt = {"id": str(uuid6.uuid7()), "prompt_id": str(uuid6.uuid7()), "commit": "12345678",
              "template": "", "metadata": {}, "type": "mustache", "variables": []}
    httpserver.expect_request("/v1/private/prompts/versions/retrieve", method="POST").respond_with_json(prompt)
    httpserver.expect_request("/v1/private/prompts/versions", method="POST").respond_with_json(prompt)

    httpserver.expect_request("/v1/private/optimizations", method="POST").respond_with_json({
        "id": str(uuid6.uuid7()), "name": "Demo optimization", "dataset_id": str(uuid6.uuid7()),
        "objective_name": "Demo objective", "status": "running", "metadata": {},
        "created_at": "2024-01-01T00:00:00Z",
    })
    httpserver.expect_request(re.compile(r"/v1/private/optimizations/.*"), method="PUT").respond_with_data(status=204)
    httpserver.expect_request("/v1/private/traces/threads/close", method="PUT").respond_with_data(status=204)
    httpserver.expect_request("/v1/private/traces/threads/feedback-scores", method="PUT").respond_with_data(status=204)


@pytest.fixture(scope="module")
def validators():
    """Seed the demo once and let every assertion below read the same captured payloads.

    Module-scoped because seeding is the expensive part; a per-test fixture would re-run the whole
    generator for each assertion. That means managing the server here rather than using the
    function-scoped `httpserver` fixture.
    """
    from pytest_httpserver import HTTPServer

    server = HTTPServer(host="localhost", port=0)
    server.start()
    try:
        # One instant for both validators, captured immediately before seeding so it sits a few
        # milliseconds ahead of the generator's own clock read. Both are then effectively the same
        # instant, and no amount of time spent inside seeding (HTTP, retries, sleeps) can drift the
        # window and fail a payload that is actually correct.
        reference_now = datetime.datetime.now()
        trace_validator = UuidWindowValidator("traces", "trace", reference_now=reference_now)
        span_validator = UuidWindowValidator("spans", "span", reference_now=reference_now)
        register_demo_mocks(server, trace_handler=trace_validator, span_handler=span_validator)
        create_demo_data(server.url_for("/"), "default", "comet_api_key")
        yield trace_validator, span_validator, reference_now
    finally:
        server.clear()
        server.stop()


class TestRejectModeSeeding:
    """The ticket's acceptance criterion: zero UUIDv7 rejections for traces and spans."""

    def test_no_trace_id_is_rejected(self, validators):
        trace_validator, _, _ = validators
        assert trace_validator.rejections == [], \
            f"{len(trace_validator.rejections)} trace ids rejected, e.g. {trace_validator.rejections[:3]}"

    def test_no_span_id_is_rejected(self, validators):
        _, span_validator, _ = validators
        assert span_validator.rejections == [], \
            f"{len(span_validator.rejections)} span ids rejected, e.g. {span_validator.rejections[:3]}"

    def test_the_clock_skew_budget_is_intact(self, validators):
        """States the slack the assertions above rely on, instead of leaving it implicit.

        The two clock reads are pinned to one instant, so those assertions cannot flake — but that
        only holds while the demo stays comfortably inside the window. This measures the actual
        margin, so shrinking it (a larger DEMO_ID_MAX_AGE, a longer dataset) shows up here as a named
        failure rather than as an occasional `too_old` somewhere else.
        """
        trace_validator, span_validator, reference_now = validators
        accepted = trace_validator.accepted + span_validator.accepted

        oldest_age = max(
            reference_now - embedded_timestamp(item["id"]) for item in accepted)
        slack = MIN_CONFIGURABLE_WINDOW - oldest_age

        assert oldest_age <= DEMO_ID_MAX_AGE + datetime.timedelta(seconds=1), (
            f"oldest id is {oldest_age} old, beyond the {DEMO_ID_MAX_AGE} age targeted by the "
            f"generator")
        assert slack >= datetime.timedelta(hours=1), (
            f"only {slack} of slack under the {MIN_CONFIGURABLE_WINDOW} floor — a clock correction "
            f"or a suspended runner of that size would start rejecting valid demo ids")

    def test_every_trace_and_span_in_the_dataset_reached_the_backend(self, validators):
        """Exact counts, not lower bounds: the acceptance criterion is that *all* of the demo lands.

        A lower bound would pass a generator that silently dropped entries, which is the failure this
        PR exists to fix — 113 of 116 traces were being rejected, and `> 100` would not have caught
        that either.

        These are the chatbot dataset's counts alone. The test-suite-experiment path does not run
        here: register_demo_mocks answers `POST /v1/private/projects/retrieve` with 404, so the
        generator skips it. That path mints ids with `uuid6.uuid7()` at call time and is unaffected by
        this change.
        """
        trace_validator, span_validator, _ = validators
        assert len(trace_validator.accepted) == len(demo_traces)
        assert len(span_validator.accepted) == len(demo_spans)

    def test_accepted_spans_reference_accepted_traces(self, validators):
        """Span -> trace references go through the not-in-future check, so they must resolve to
        traces that were themselves accepted."""
        trace_validator, span_validator, _ = validators
        trace_ids = {item["id"] for item in trace_validator.accepted}
        dangling = {item["trace_id"] for item in span_validator.accepted} - trace_ids
        assert dangling == set(), f"{len(dangling)} spans reference traces that never landed"

    def test_the_span_parent_graph_survives_the_id_remap(self, validators):
        """Every parent_span_id must point at a span that was actually emitted.

        The sibling test covers dangling trace_id; this covers the other reference. Both matter
        because this PR rewrote the remapping — build_span_writes maps id, trace_id and
        parent_span_id through separate lookups, so a mistake in one produces a span whose own id is
        perfectly valid (and passes every window check) while its parent points at nothing.
        SpanService rejects that; a mock that only inspects `id` would not.
        """
        trace_validator, span_validator, _ = validators
        span_ids = {item["id"] for item in span_validator.accepted}

        dangling = {
            item["parent_span_id"]
            for item in span_validator.accepted
            if item.get("parent_span_id")
        } - span_ids
        assert dangling == set(), f"{len(dangling)} parent_span_id(s) reference no emitted span"

        # One root per trace, matching the dataset: a remap that collapsed or invented parents would
        # change this count even while every individual reference resolved.
        roots = [
            item for item in span_validator.accepted if not item.get("parent_span_id")
        ]
        assert len(roots) == len(trace_validator.accepted)
        assert {item["trace_id"] for item in roots} == {
            item["id"] for item in trace_validator.accepted
        }

    def test_reference_fields_are_also_v7(self, validators):
        """IdGenerator.validateVersion applies to the ids a span points at, not just its own."""
        _, span_validator, _ = validators
        referenced = {item["trace_id"] for item in span_validator.accepted} | {
            item["parent_span_id"]
            for item in span_validator.accepted
            if item.get("parent_span_id")
        }

        versions = {uuid.UUID(value).version for value in referenced}
        assert versions == {7}, f"non-v7 ids referenced by spans: {versions}"

    def test_every_accepted_id_is_a_v7_uuid(self, validators):
        """The ticket states the always-on version check passes because all ids are v7 — assert it.

        `uuid7_from_datetime` sets the version nibble by hand, so this is the guard on that hand-rolled
        builder: a mistake there would produce ids that clear the window but are rejected by
        IdGenerator.validateVersion in production.
        """
        trace_validator, span_validator, _ = validators
        versions = {
            uuid.UUID(item["id"]).version
            for item in trace_validator.accepted + span_validator.accepted
        }
        assert versions == {7}, f"non-v7 ids in the seed: {versions}"

    def test_ids_are_unique_across_the_seed(self, validators):
        trace_validator, span_validator, _ = validators
        ids = [item["id"] for item in trace_validator.accepted + span_validator.accepted]
        assert len(set(ids)) == len(ids)

    def test_span_timestamps_sit_inside_their_trace(self, validators):
        """The rebase has to hold on the real payloads, not just in the timeline unit tests."""
        trace_validator, span_validator, _ = validators
        traces = {item["id"]: item for item in trace_validator.accepted}

        def parse(value):
            return datetime.datetime.fromisoformat(value.replace("Z", "+00:00"))

        tolerance = datetime.timedelta(milliseconds=1)
        outside = []
        for span in span_validator.accepted:
            parent = traces[span["trace_id"]]
            if (parse(span["start_time"]) < parse(parent["start_time"])
                    or parse(span["end_time"]) > parse(parent["end_time"]) + tolerance):
                outside.append(span["id"])

        assert outside == [], f"{len(outside)} spans fall outside their parent trace's window"


class TestValidatorItself:
    """The mock validator is the whole basis of the tests above, so prove it can fail."""

    def test_rejects_an_id_that_is_too_old(self):
        validator = UuidWindowValidator("traces", "trace")
        old = datetime.datetime.now() - datetime.timedelta(days=30)
        # Built with the generator's own minter rather than by hand: hand-assembling the version
        # nibble leaves the RFC-4122 variant bits clear, so uuid.UUID.version returns None and the
        # id would trip the version check instead of the too_old check this test is about.
        stale_id = uuid7_from_datetime(old)
        assert stale_id.version == 7

        response = validator(FakeRequest({"traces": [{"id": str(stale_id)}]}))

        assert response.status_code == 400
        assert [entry[3] for entry in validator.rejections] == ["too_old"]

    def test_rejects_a_non_v7_id_even_when_its_timestamp_is_in_window(self):
        """Production checks the version unconditionally, so an in-window v4 must still be a 400."""
        validator = UuidWindowValidator("traces", "trace")

        response = validator(FakeRequest({"traces": [{"id": str(uuid.uuid4())}]}))

        assert response.status_code == 400
        assert [entry[3] for entry in validator.rejections] == ["not_version_7"]

    def test_never_accepts_a_body_it_could_not_decode(self):
        """A 204 here would mean the mock passed a request whose contents it never saw."""
        validator = UuidWindowValidator("traces", "trace")

        response = validator(FakeRequest(raw=b"\x1f\x8b not gzip, not json"))

        assert response.status_code == 400
        assert [entry[3] for entry in validator.rejections] == ["undecodable_body"]

    def test_accepts_an_id_from_now(self):
        validator = UuidWindowValidator("traces", "trace")

        response = validator(FakeRequest({"traces": [{"id": str(uuid6.uuid7())}]}))

        assert response.status_code == 204
        assert validator.rejections == []


class FakeRequest:
    def __init__(self, payload=None, raw=None):
        self._payload = raw if raw is not None else json.dumps(payload).encode("utf-8")

    def get_data(self):
        return self._payload
