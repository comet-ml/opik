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

from opik_backend.demo_data_generator import create_demo_data

# The smallest window an operator can configure. Validating against it covers every larger one.
MIN_CONFIGURABLE_WINDOW = datetime.timedelta(hours=12)


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
    return {"__undecodable__": body[:32]}


class UuidWindowValidator:
    """Mock ingestion endpoint enforcing the UUIDv7 window in reject mode."""

    def __init__(self, payload_key, resource, window=MIN_CONFIGURABLE_WINDOW):
        self.payload_key = payload_key
        self.resource = resource
        self.window = window
        self.accepted = []
        self.rejections = []

    def __call__(self, request):
        from werkzeug.wrappers import Response

        now = datetime.datetime.now()
        items = decode_payload(request).get(self.payload_key, [])

        for item in items:
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


def register_demo_mocks(httpserver, trace_handler=None, span_handler=None):
    """Register the backend endpoints demo seeding touches.

    Mirrors the happy-path mocks in test_demo_data_generator; the trace/span batch endpoints are
    overridable so a test can assert on the payloads that actually go over the wire.
    """
    httpserver.expect_request("/is-alive/ping", method="GET").respond_with_data("pong", status=200)
    httpserver.expect_request("/v1/private/projects/retrieve", method="POST").respond_with_data(status=404)
    httpserver.expect_request("/v1/private/projects", method="GET").respond_with_json(
        {"content": [], "page": 1, "size": 0, "total": 0})
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
        trace_validator = UuidWindowValidator("traces", "trace")
        span_validator = UuidWindowValidator("spans", "span")
        register_demo_mocks(server, trace_handler=trace_validator, span_handler=span_validator)
        create_demo_data(server.url_for("/"), "default", "comet_api_key")
        yield trace_validator, span_validator
    finally:
        server.clear()
        server.stop()


class TestRejectModeSeeding:
    """The ticket's acceptance criterion: zero UUIDv7 rejections for traces and spans."""

    def test_no_trace_id_is_rejected(self, validators):
        trace_validator, _ = validators
        assert trace_validator.rejections == [], \
            f"{len(trace_validator.rejections)} trace ids rejected, e.g. {trace_validator.rejections[:3]}"

    def test_no_span_id_is_rejected(self, validators):
        _, span_validator = validators
        assert span_validator.rejections == [], \
            f"{len(span_validator.rejections)} span ids rejected, e.g. {span_validator.rejections[:3]}"

    def test_traces_and_spans_actually_reached_the_backend(self, validators):
        """Guards against the assertions above passing because nothing was posted at all."""
        trace_validator, span_validator = validators
        assert len(trace_validator.accepted) > 100
        assert len(span_validator.accepted) > 100

    def test_accepted_spans_reference_accepted_traces(self, validators):
        """Span -> trace references go through the not-in-future check, so they must resolve to
        traces that were themselves accepted."""
        trace_validator, span_validator = validators
        trace_ids = {item["id"] for item in trace_validator.accepted}
        dangling = {item["trace_id"] for item in span_validator.accepted} - trace_ids
        assert dangling == set(), f"{len(dangling)} spans reference traces that never landed"

    def test_ids_are_unique_across_the_seed(self, validators):
        trace_validator, span_validator = validators
        ids = [item["id"] for item in trace_validator.accepted + span_validator.accepted]
        assert len(set(ids)) == len(ids)

    def test_span_timestamps_sit_inside_their_trace(self, validators):
        """The rebase has to hold on the real payloads, not just in the timeline unit tests."""
        trace_validator, span_validator = validators
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
        stale_id = uuid.UUID(int=(int(old.timestamp() * 1000) << 80) | (0x7 << 76))

        response = validator(FakeRequest({"traces": [{"id": str(stale_id)}]}))

        assert response.status_code == 400
        assert [entry[3] for entry in validator.rejections] == ["too_old"]

    def test_accepts_an_id_from_now(self):
        validator = UuidWindowValidator("traces", "trace")

        response = validator(FakeRequest({"traces": [{"id": str(uuid6.uuid7())}]}))

        assert response.status_code == 204
        assert validator.rejections == []


class FakeRequest:
    def __init__(self, payload):
        self._payload = json.dumps(payload).encode("utf-8")

    def get_data(self):
        return self._payload
