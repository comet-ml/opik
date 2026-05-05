import opik
import json
import urllib.request
import uuid6
import logging
import datetime
import time
import uuid
import random

import opik.rest_api
from opik.rest_api.types.trace_write import TraceWrite
from opik.rest_api.types.span_write import SpanWrite
from opik.rest_api.types.feedback_score_batch_item import FeedbackScoreBatchItem
from opik.rest_api.types.experiment_item import ExperimentItem as RestExperimentItem
from opik_backend.demo_data import (
    demo_traces,
    demo_spans,
    demo_thread_feedback_scores,
    demo_test_suite_items,
    demo_test_suite_global_assertion,
    demo_agent_config_blueprints,
    demo_test_suite_experiments,
    demo_agent_prompt_name,
    demo_agent_prompt_v1,
    demo_agent_prompt_v2,
)
from opentelemetry import trace
from dataclasses import dataclass, field

logger = logging.getLogger(__name__)
tracer = trace.get_tracer(__name__)

@dataclass
class DemoDataContext:
    """Context object to hold state for a single demo data creation invocation.
    This prevents race conditions when multiple users sign up concurrently."""
    uuid_map: dict = field(default_factory=dict)
    trace_time_shift: dict = field(default_factory=dict)


def make_http_request(base_url, message, workspace_name, comet_api_key):
    try:
        headers = {}
        if workspace_name:
            headers["Comet-Workspace"] = workspace_name

        if comet_api_key:
            headers["authorization"] = f"{comet_api_key}"

        url = base_url + message["url"]
        data = json.dumps(message["payload"]).encode("utf-8") if "payload" in message else None

        req = urllib.request.Request(url, data=data, method=message["method"])
        for key, value in headers.items():
            req.add_header(key, value)
        req.add_header("Content-Type", "application/json")

        with urllib.request.urlopen(req) as response:
            status_code = response.getcode()
            logger.info("Got response status %s, from method %s on url %s", status_code, message["method"], url)
            body = response.read()
            if body:
                data = json.loads(body)
            else:
                data = None
            return data, status_code
    except urllib.error.HTTPError as e:
        if e.code >= 500:
            raise e
        logger.info("Got error %s, from method %s on url %s", e.code, message["method"], url)
        return None, e.code


def create_feedback_scores_definition(base_url, workspace_name, comet_api_key):
    name = "User feedback"
    params = {
        "name": name
    }
    request = {
        "url": f"/v1/private/feedback-definitions?{urllib.parse.urlencode(params)}",
        "method": "GET"
    }

    data, status_code = make_http_request(base_url, request, workspace_name, comet_api_key)

    if status_code == 200 and data["content"]:
        for definition in data["content"]:
            if definition["name"] == name:
                logger.info("Feedback definition already exists")
                return

    request = {
        "url": "/v1/private/feedback-definitions",
        "method": "POST",
        "payload": {
            "name": name,
            "description": "Feedback provided by the user",
            "type": "categorical",
            "details": {
                "categories": {
                    "👍": 1.0,
                    "👎": 0.0
                }
            },
        },
    }

    make_http_request(base_url, request, workspace_name, comet_api_key)


def create_default_environments(base_url, workspace_name, comet_api_key):
    defaults = [
        {"name": "development", "position": 0, "color": "#EF6868"},
        {"name": "staging", "position": 1, "color": "#F4B400"},
        {"name": "production", "position": 2, "color": "#19A979"},
    ]
    for payload in defaults:
        request = {
            "url": "/v1/private/environments",
            "method": "POST",
            "payload": payload,
        }
        make_http_request(base_url, request, workspace_name, comet_api_key)


def create_project(base_url, workspace_name, comet_api_key, project_name):
    request = {
        "url": "/v1/private/projects",
        "method": "POST",
        "payload": {
            "name": project_name,
        },
    }

    _, status_code = make_http_request(base_url, request, workspace_name, comet_api_key)
    return status_code

def calculate_time_shift_to_now(traces):
    """
    Calculate time shift to move the latest end_time to 'now' while preserving time differences.

    Args:
        traces: List of trace dictionaries with 'start_time' and 'end_time' keys

    Returns:
        datetime.timedelta: The time shift to apply to all timestamps
    """
    if not traces:
        return datetime.timedelta(0)

    # Find the maximum end_time among all traces
    traces_with_end_time = [trace['end_time'] for trace in traces if 'end_time' in trace]
    if not traces_with_end_time:
        return datetime.timedelta(0)

    max_end_time = max(traces_with_end_time)

    # Current time (now)
    now = datetime.datetime.now()

    # Calculate shift to move max_end_time to now
    time_shift = now - max_end_time

    return time_shift

def apply_time_shift(time_object, time_shift):
    """
    Apply a time shift to a datetime object.

    Args:
        time_object: datetime object to shift
        time_shift: datetime.timedelta to apply

    Returns:
        datetime: Shifted datetime object
    """
    return time_object + time_shift

def set_time_shift(context: DemoDataContext, trace_id, time_shift):
    context.trace_time_shift[trace_id] = time_shift

def get_time_shift(context: DemoDataContext, trace_id):
    """
    Get the time shift value for a given trace ID from the context.

    Parameters:
    - context: DemoDataContext object holding the state
    - trace_id (string): The trace ID to retrieve the time shift value for.

    Returns:
    datetime.timedelta: The time shift value for the provided trace ID. If the trace ID is not found in the dictionary, a default value of 0 timedelta is returned.
    """
    if trace_id in context.trace_time_shift:
        time_shift = context.trace_time_shift[trace_id]
        return time_shift
    return datetime.timedelta(0)

def process_traces_with_time_shift(traces, context: DemoDataContext, project_name: str):
    """
    Process traces with proper time shifts and time-based UUIDs, returning a list of
    TraceWrite objects ready for a single synchronous POST /v1/private/traces/batch call.

    Shifts all trace timestamps to present time while preserving temporal relationships,
    and generates time-based UUIDs for consistent ordering.

    Calculates time shift from the latest end_time across traces only
    to ensure all data is brought to present while preserving time distances.

    Parameters:
    - traces: List of trace dictionaries to process
    - context: DemoDataContext object holding state
    - project_name: Project name to attach to every TraceWrite

    Returns:
    - tuple: (list[TraceWrite] ready to POST, datetime.timedelta time_shift applied)
    """
    # Calculate time shift from traces only to move latest trace end_time to now
    # This same shift will be applied to spans to preserve all time distances
    time_shift = calculate_time_shift_to_now(traces)

    # Track per-millisecond counters to break ties when multiple traces share the same
    # start_time down to the millisecond. uuid7_from_datetime derives the UUID's temporal
    # prefix from the ms-resolution timestamp, so two traces landing on the same ms can
    # produce UUIDs that collide (or get silently deduplicated by the backend). Adding a
    # monotonic microsecond offset ensures each trace gets a distinct UUID7 prefix.
    ms_counter: dict = {}
    trace_writes: list = []

    for idx, original_trace in enumerate(sorted(traces, key=lambda x: x["id"])):
        # Create a copy to avoid mutating the original demo_data
        trace = dict(original_trace)
        # Store the old ID before modification
        old_trace_id = trace["id"]
        # Apply time shift to maintain time differences
        shifted_start = apply_time_shift(trace["start_time"], time_shift)
        shifted_end = apply_time_shift(trace["end_time"], time_shift)

        # Deduplicate sub-millisecond timestamps: if multiple traces land on the same
        # millisecond after the shift, offset each by 1 µs so their UUID7s are distinct.
        ms_key = int(shifted_start.timestamp() * 1000)
        offset_us = ms_counter.get(ms_key, 0)
        ms_counter[ms_key] = offset_us + 1
        if offset_us > 0:
            shifted_start = shifted_start + datetime.timedelta(microseconds=offset_us)
            # Shift end_time by the same amount so duration is preserved and we never
            # end up with end_time < start_time (would violate ordering invariants).
            shifted_end = shifted_end + datetime.timedelta(microseconds=offset_us)

        trace["start_time"] = shifted_start
        trace["end_time"] = shifted_end
        new_id = get_new_uuid_by_time(context, old_trace_id, trace["start_time"])
        trace["id"] = new_id
        # Attach project_name directly so the TraceWrite targets the right project.
        trace["project_name"] = project_name
        # Remove fields that shouldn't be in the trace payload
        trace.pop("project_id", None)
        trace.pop("workspace_id", None)
        set_time_shift(context, new_id, time_shift)
        trace_writes.append(TraceWrite(**trace))

    return trace_writes, time_shift

def process_spans_with_time_shift(spans, time_shift, context: DemoDataContext, project_name: str):
    """
    Process spans with the same time shift as their parent traces, returning a list of
    SpanWrite objects ready for a single synchronous POST /v1/private/spans/batch call.

    Spans use the time shift passed from trace processing to maintain relative timing.

    First pass generates all time-based UUIDs and stores time shifts.
    Second pass builds all SpanWrite objects with properly shifted times and parent_span_id mappings.

    Parameters:
    - spans: List of span dictionaries to process
    - time_shift: The time shift to apply (from traces)
    - context: DemoDataContext object holding state
    - project_name: Project name to attach to every SpanWrite

    Returns:
    - list[SpanWrite]: Spans ready to POST
    """
    # First pass: Generate all time-based UUIDs and store time shifts for spans
    # This ensures all parent span IDs are mapped before we reference them
    span_time_shifts = {}
    for original_span in sorted(spans, key=lambda x: x["id"]):
        # Create a copy to avoid mutating the original demo_data
        span = dict(original_span)
        # Store the old ID before modification
        old_span_id = span["id"]
        # Apply the same time shift as parent trace to maintain relative timing
        span["start_time"] = apply_time_shift(span["start_time"], time_shift)
        span["end_time"] = apply_time_shift(span["end_time"], time_shift)
        # Generate time-based UUID based on shifted start_time (consistent with traces)
        new_id = get_new_uuid_by_time(context, old_span_id, span["start_time"])
        # Remove fields that shouldn't be in the span data
        span.pop("project_id", None)
        span.pop("workspace_id", None)
        # Store the time shift for use in second pass
        span_time_shifts[old_span_id] = (span["start_time"], span["end_time"], time_shift)

    # Second pass: build SpanWrite objects with properly shifted times and parent_span_id mappings
    span_writes: list = []
    for original_span in sorted(spans, key=lambda x: x["id"]):
        # Create a copy to avoid mutating the original demo_data
        span = dict(original_span)
        # Use the stored time shifts from first pass
        old_span_id = original_span["id"]
        if old_span_id in span_time_shifts:
            span["start_time"], span["end_time"], _ = span_time_shifts[old_span_id]
        # Use the mapped UUIDs from context
        span["id"] = get_new_uuid(context, original_span["id"])
        span["trace_id"] = get_new_uuid(context, original_span["trace_id"])
        if "parent_span_id" in span:
            new_parent_span_id = get_new_uuid(context, span["parent_span_id"])
            span["parent_span_id"] = new_parent_span_id
        # Attach project_name directly so the SpanWrite targets the right project.
        span["project_name"] = project_name
        # Remove fields that shouldn't be in the span payload
        span.pop("project_id", None)
        span.pop("workspace_id", None)
        span_writes.append(SpanWrite(**span))

    return span_writes

def uuid7_from_datetime(dt: datetime.datetime) -> uuid.UUID:

    # 1. Get timestamp in milliseconds and microseconds
    timestamp_ms = int(dt.timestamp() * 1000)  # 48 bits
    micros = dt.microsecond  # 0–999999

    # 2. Use 12 bits for sub-millisecond part: scale microseconds (0–999999) to 0–4095
    sub_ms_bits = int(micros * 4096 / 1_000_000)  # 12 bits

    # 3. Split 48-bit timestamp into time fields
    time_low = (timestamp_ms >> 16) & 0xFFFFFFFF
    time_mid = timestamp_ms & 0xFFFF
    time_hi = (sub_ms_bits & 0x0FFF)  # 12-bit sub-millisecond precision
    time_hi_and_version = (0x7 << 12) | time_hi  # version (4 bits) + sub-ms (12 bits)

    # 4. 14 random bits for clock sequence
    clock_seq = random.getrandbits(14)
    clock_seq_low = clock_seq & 0xFF
    clock_seq_hi_variant = 0x80 | ((clock_seq >> 8) & 0x3F)  # variant '10xxxxxx'

    # 5. 48 random bits for node
    node = random.getrandbits(48)

    # 6. Construct UUID
    return uuid.UUID(fields=(
        time_low,
        time_mid,
        time_hi_and_version,
        clock_seq_hi_variant,
        clock_seq_low,
        node
    ))

def get_new_uuid(context: DemoDataContext, old_id):
    """
    The demo_data has the IDs hardcoded in, to preserve the relationships between the traces and spans.
    However, we need to generate unique ones before logging them.
    """
    if old_id in context.uuid_map:
        new_id = context.uuid_map[old_id]
    else:
        new_id = str(uuid6.uuid7())
        context.uuid_map[old_id] = new_id
    return new_id

def get_new_uuid_by_time(context: DemoDataContext, old_id, datetime):
    """
    The demo_data has the IDs hardcoded in, to preserve the relationships between the traces and spans.
    However, we need to generate unique ones before logging them based on start_time.
    """
    if old_id in context.uuid_map:
        new_id = context.uuid_map[old_id]
    else:
        new_id = str(uuid7_from_datetime(datetime))
        context.uuid_map[old_id] = new_id
    return new_id

def create_demo_chatbot_project(context: DemoDataContext, base_url: str, workspace_name, comet_api_key):
    """Seed the demo chatbot project. Returns True when fresh data was written,
    False when the project already existed (409) and the seed was skipped."""
    with tracer.start_as_current_span("create_demo_chatbot_project"):
        client: opik.Opik = None

        try:
            project_name = "Opik Demo Agent Observability"

            # Create the project explicitly before sending traces.
            # This is the single source of truth for whether demo data creation proceeds:
            # - 201: project created, continue
            # - 409: project already exists (signup hook re-run), skip entirely — idempotent
            # - other: likely API key not yet propagated on fresh signup, retry with backoff
            # We can't rely on implicit project creation via the SDK's batch trace endpoint because
            # it silently swallows 4xx errors (flush() returns True even when backend rejects traces).
            max_retries = 5
            status_code = None
            for attempt in range(max_retries):
                status_code = create_project(base_url, workspace_name, comet_api_key, project_name)
                if status_code == 201 or status_code == 409:
                    break
                if attempt == max_retries - 1:
                    logger.error("Failed to create project %s for workspace %s after %d retries (last status=%s), aborting demo data creation", project_name, workspace_name, max_retries, status_code)
                    return False
                time.sleep(2 ** attempt)

            if status_code == 409:
                logger.info("%s project already exists for workspace %s, skipping demo data creation", project_name, workspace_name)
                return False

            client = opik.Opik(
                project_name=project_name,
                workspace=workspace_name,
                host=base_url,
                api_key=comet_api_key,
                _use_batching=True,
            )

            # Sanity check: warn if the demo dataset itself contains duplicate trace IDs
            # (these would collapse to a single row on ingest).
            trace_ids = [t["id"] for t in demo_traces]
            seen_trace_ids: set = set()
            for tid in trace_ids:
                if tid in seen_trace_ids:
                    logger.warning("Duplicate trace id found in demo data: %s", tid)
                seen_trace_ids.add(tid)
            logger.info("Found %d unique trace IDs in demo data", len(seen_trace_ids))

            # Extract unique thread IDs before processing traces
            threads = list({trace["thread_id"] for trace in demo_traces if "thread_id" in trace and trace["thread_id"] is not None})

            logger.info("Creating %d threads, %d traces, %d spans for workspace %s",
                        len(threads), len(demo_traces), len(demo_spans), workspace_name)

            # Build the full TraceWrite / SpanWrite payloads up-front, then POST them
            # synchronously via the REST client. This deliberately bypasses the SDK's
            # batch queue because:
            #   - batch queue silently swallows 401s via the unauthorized-message
            #     registry (10s block), so client.flush() can return True even when
            #     nothing landed on the backend;
            #   - the raw REST call returns a real HTTP status and raises ApiError on
            #     non-2xx, which propagates up to our outer except as a loud failure;
            #   - no ClickHouse read is needed for verification, so we avoid false
            #     positives from CH replica lag.
            trace_writes, time_shift = process_traces_with_time_shift(demo_traces, context, project_name)
            logger.info("Posting %d traces synchronously via REST for workspace %s", len(trace_writes), workspace_name)
            client.rest_client.traces.create_traces(traces=trace_writes)

            span_writes = process_spans_with_time_shift(demo_spans, time_shift, context, project_name)
            logger.info("Posting %d spans synchronously via REST for workspace %s", len(span_writes), workspace_name)
            client.rest_client.spans.create_spans(spans=span_writes)

            done = False
            max_attempts = 10
            attempts = 0

            while not done and attempts < max_attempts:
                try:
                    client.rest_client.traces.close_trace_thread(project_name=project_name, thread_ids=threads)
                    done = True
                    attempts = 0
                except Exception as e:
                    logger.error("Error closing threads for workspace %s attempt %d: status=%s, body=%s", workspace_name, attempts, getattr(e, 'status_code', 'unknown'), getattr(e, 'body', str(e)))
                    attempts += 1
                    time.sleep(0.5)

            all_scores = []

            for thread, items in demo_thread_feedback_scores.items():
                if not items:  # skip empty lists
                    logger.info("No feedback scores for thread %s", thread)
                    continue

                all_scores.extend(
                    opik.rest_api.types.FeedbackScoreBatchItemThread(
                        thread_id=thread,
                        project_name=project_name,
                        name=item['name'],
                        category_name=item.get('category_name'),
                        value=item['value'],
                        reason=item.get('reason'),
                        source=item.get('source', 'sdk')
                    )
                    for item in items
                )

            if all_scores:
                done = False
                max_attempts = 10
                attempts = 0
                while not done and attempts < max_attempts:
                    try:
                        client.rest_client.traces.score_batch_of_threads(scores=all_scores)
                        done = True
                        attempts = 0
                    except Exception as e:
                        logger.error("Error scoring batch of threads for workspace %s attempt %d: status=%s, body=%s", workspace_name, attempts, getattr(e, 'status_code', 'unknown'), getattr(e, 'body', str(e)))
                        attempts += 1
                        time.sleep(0.5)
                if not done:
                    logger.error("Failed to score batch of threads for workspace %s after %d attempts", workspace_name, max_attempts)

            return True
        except Exception as e:
            logger.error("Error creating demo chatbot project for workspace %s: %s", workspace_name, e, exc_info=True)
            return False
        finally:
            # Close the client
            if client:
                client.flush()
                client.end()

def retrieve_project_id_by_name(base_url, workspace_name, comet_api_key, project_name):
    request = {
        "url": "/v1/private/projects/retrieve",
        "method": "POST",
        "payload": {"name": project_name},
    }
    data, status_code = make_http_request(base_url, request, workspace_name, comet_api_key)
    if status_code == 200 and data and "id" in data:
        return data["id"]
    return None


def create_agent_config_blueprint(base_url, workspace_name, comet_api_key, project_id, blueprint_values, description):
    """Create or patch an agent config blueprint and return its id+name.

    Checks GET /latest to decide between POST (no config yet) and PATCH
    (adding another blueprint to existing config). POST fails with 409 when
    a config already exists, which is why we dispatch explicitly instead of
    always using POST.

    The POST response uses the Write JsonView which omits the auto-generated
    name, and PATCH returns no body at all. We fetch via GET /latest
    afterwards (Public view) to obtain the name.
    """
    latest_request = {
        "url": f"/v1/private/agent-configs/blueprints/latest/projects/{project_id}",
        "method": "GET",
    }
    _, latest_status = make_http_request(base_url, latest_request, workspace_name, comet_api_key)
    method = "POST" if latest_status == 404 else "PATCH"
    create_request = {
        "url": "/v1/private/agent-configs/blueprints",
        "method": method,
        "payload": {
            "project_id": project_id,
            "blueprint": {
                "type": "blueprint",
                "description": description,
                "values": blueprint_values,
            },
        },
    }
    data, status_code = make_http_request(base_url, create_request, workspace_name, comet_api_key)
    if status_code not in (200, 201):
        logger.error("Failed to create blueprint: status=%s, data=%s", status_code, data)
        return None, None

    # Response omits name (Write view). Re-fetch as Public view to get the name.
    latest_request = {
        "url": f"/v1/private/agent-configs/blueprints/latest/projects/{project_id}",
        "method": "GET",
    }
    latest_data, latest_status = make_http_request(base_url, latest_request, workspace_name, comet_api_key)
    if latest_status != 200 or not latest_data:
        logger.error("Failed to fetch latest blueprint after create: status=%s", latest_status)
        return None, None
    return latest_data.get("id"), latest_data.get("name")


def tag_agent_config_env(base_url, workspace_name, comet_api_key, project_id, env_name, blueprint_name):
    request = {
        "url": f"/v1/private/agent-configs/blueprints/environments/{env_name}/projects/{project_id}",
        "method": "PUT",
        "payload": {"blueprint_name": blueprint_name},
    }
    _, status_code = make_http_request(base_url, request, workspace_name, comet_api_key)
    if status_code != 204:
        logger.error("Failed to tag env '%s' → '%s': status=%s", env_name, blueprint_name, status_code)


def _build_suite_span_tree(trace_id, question, answer, trace_start, trace_end,
                           project_name, model, env):
    """Build an 8-span agent tree mirroring the existing chatbot demo structure.

    Structure:
      handle_query (root, general)
      ├── classify_query (general)
      │   └── chat_completion_create (llm)
      ├── contact_insight_tool (tool)
      │   ├── retrieve_documents_rag (general)
      │   └── chat_completion_create (llm)
      └── format_response (general)
          └── chat_completion_create (llm)
    """
    total = (trace_end - trace_start).total_seconds()
    # Divide the trace into 6 sequential segments (3 main stages × 2 sub-stages).
    seg = datetime.timedelta(seconds=total / 6.0)

    s0, s1, s2, s3, s4, s5, s6 = [trace_start + i * seg for i in range(7)]
    # s0..s1: classify_query | s1..s3: contact_insight_tool | s3..s5: format_response

    root_id = str(uuid6.uuid7())
    classify_id = str(uuid6.uuid7())
    classify_llm_id = str(uuid6.uuid7())
    tool_id = str(uuid6.uuid7())
    retrieve_id = str(uuid6.uuid7())
    tool_llm_id = str(uuid6.uuid7())
    format_id = str(uuid6.uuid7())
    format_llm_id = str(uuid6.uuid7())

    def llm_span(span_id, parent_id, start, end, user_prompt, assistant_output,
                 prompt_tokens, completion_tokens):
        return SpanWrite(
            id=span_id, trace_id=trace_id, parent_span_id=parent_id,
            project_name=project_name, name="chat_completion_create", type="llm",
            start_time=start, end_time=end,
            input={"messages": [
                {"role": "system", "content": "You are a helpful support agent."},
                {"role": "user", "content": user_prompt},
            ]},
            output={"choices": [
                {"index": 0, "finish_reason": "stop",
                 "message": {"role": "assistant", "content": assistant_output}}
            ]},
            model=model, provider="openai",
            usage={
                "prompt_tokens": prompt_tokens,
                "completion_tokens": completion_tokens,
                "total_tokens": prompt_tokens + completion_tokens,
            },
            tags=[env],
            source="experiment",
        )

    return [
        SpanWrite(
            id=root_id, trace_id=trace_id, parent_span_id=None,
            project_name=project_name, name="handle_query", type="general",
            start_time=trace_start, end_time=trace_end,
            input={"question": question},
            output={"answer": answer},
            tags=[env],
            source="experiment",
        ),
        SpanWrite(
            id=classify_id, trace_id=trace_id, parent_span_id=root_id,
            project_name=project_name, name="classify_query", type="general",
            start_time=s0, end_time=s1,
            input={"query": question, "model": model},
            output={"category": "support"},
            tags=[env],
            source="experiment",
        ),
        llm_span(classify_llm_id, classify_id, s0, s1,
                 user_prompt=f"Classify the following user query into a support category: {question}",
                 assistant_output="support",
                 prompt_tokens=65, completion_tokens=3),
        SpanWrite(
            id=tool_id, trace_id=trace_id, parent_span_id=root_id,
            project_name=project_name, name="contact_insight_tool", type="tool",
            start_time=s1, end_time=s3,
            input={"query": question, "model": model},
            output={"documents_found": 3, "context_preview": answer[:120]},
            tags=[env],
            source="experiment",
        ),
        SpanWrite(
            id=retrieve_id, trace_id=trace_id, parent_span_id=tool_id,
            project_name=project_name, name="retrieve_documents_rag", type="general",
            start_time=s1, end_time=s2,
            input={"category": "support"},
            output={"documents": [{"title": "Opik docs excerpt", "snippet": answer[:160]}]},
            tags=[env],
            source="experiment",
        ),
        llm_span(tool_llm_id, tool_id, s2, s3,
                 user_prompt=(f"Given the retrieved context about Opik, answer the user's question: "
                              f"{question}"),
                 assistant_output=answer,
                 prompt_tokens=180, completion_tokens=max(32, len(answer.split()) * 2)),
        SpanWrite(
            id=format_id, trace_id=trace_id, parent_span_id=root_id,
            project_name=project_name, name="format_response", type="general",
            start_time=s3, end_time=s5,
            input={"user_query": question, "raw_tool_output": answer, "model": model},
            output={"answer": answer},
            tags=[env],
            source="experiment",
        ),
        llm_span(format_llm_id, format_id, s3, s5,
                 user_prompt=(f"Format the following answer as a clear, concise reply: {answer}"),
                 assistant_output=answer,
                 prompt_tokens=120, completion_tokens=max(24, len(answer.split()) * 2)),
    ]


def create_demo_test_suite_and_experiments(base_url: str, workspace_name, comet_api_key, project_name):
    """Seed a test suite, agent-config blueprints, and two experiments.

    Assumes the demo project was just created. Uses the existing feedback-score
    batch endpoint with category_name='suite_assertion' to route scores to the
    assertion_results table (ScoreDestination.ASSERTION_RESULTS).
    """
    with tracer.start_as_current_span("create_demo_test_suite_and_experiments"):
        client: opik.Opik = None
        try:
            project_id = retrieve_project_id_by_name(base_url, workspace_name, comet_api_key, project_name)
            if project_id is None:
                logger.error("Demo project '%s' not found, skipping test suite creation", project_name)
                return

            client = opik.Opik(
                project_name=project_name,
                workspace=workspace_name,
                host=base_url,
                api_key=comet_api_key,
                _use_batching=True,
            )

            # Step 1 — evaluation suite (renamed to "test suite" on main, but this
            # codepath uses the 1.10.56 SDK naming for evaluation suites).
            suite_name = "Customer support regression suite"
            suite = client.get_or_create_evaluation_suite(
                name=suite_name,
                description="Demo regression tests for the support agent.",
                assertions=[demo_test_suite_global_assertion],
                project_name=project_name,
            )
            if suite.get_items():
                logger.info("Evaluation suite '%s' already populated, skipping", suite_name)
                return

            suite.add_items([
                {
                    "data": {"question": template["question"]},
                    "assertions": template["assertions"],
                }
                for template in demo_test_suite_items
            ])
            client.flush()

            # After insert, map back: question text → backend-assigned item id.
            inserted_items = suite.get_items()
            item_id_by_question = {item["data"]["question"]: item["id"] for item in inserted_items}
            logger.info("Inserted %d evaluation suite items for workspace %s",
                        len(inserted_items), workspace_name)

            # Step 2a — create the support agent system prompt with two commits.
            # Each blueprint pins the `system_prompt` key to the commit matching
            # its prompt_version so the UI shows a real prompt link, not a raw string.
            prompt_v1 = client.create_prompt(
                name=demo_agent_prompt_name,
                prompt=demo_agent_prompt_v1,
                description="Support agent system prompt used by the demo project.",
                project_name=project_name,
            )
            prompt_v2 = client.create_prompt(
                name=demo_agent_prompt_name,
                prompt=demo_agent_prompt_v2,
                change_description="Ground answers in retrieved context and enforce 120-word cap.",
                project_name=project_name,
            )
            prompt_commit_by_version = {"v1": prompt_v1.commit, "v2": prompt_v2.commit}
            logger.info("Created prompt '%s' with commits v1=%s v2=%s",
                        demo_agent_prompt_name, prompt_v1.commit, prompt_v2.commit)

            # Step 2b — agent config blueprints. The helper auto-detects POST vs PATCH.
            blueprint_by_env = {}
            for bp in demo_agent_config_blueprints:
                values_with_prompt = list(bp["values"]) + [{
                    "key": "system_prompt",
                    "type": "prompt",
                    "value": prompt_commit_by_version[bp["prompt_version"]],
                    "description": f"System prompt pinned to {bp['prompt_version']}",
                }]
                bp_id, bp_name = create_agent_config_blueprint(
                    base_url, workspace_name, comet_api_key, project_id,
                    values_with_prompt, bp["description"],
                )
                if bp_id is None:
                    logger.error("Aborting test suite seed — blueprint creation failed for env %s", bp["env"])
                    return
                blueprint_by_env[bp["env"]] = {"id": bp_id, "name": bp_name}
                tag_agent_config_env(base_url, workspace_name, comet_api_key, project_id, bp["env"], bp_name)

            # Step 3 — experiments + traces + scores.
            suite_dataset_name = suite.dataset.name
            now = datetime.datetime.now()

            for exp_cfg in demo_test_suite_experiments:
                env = exp_cfg["tag"]
                blueprint = blueprint_by_env[env]
                # Resolve the model value from the blueprint for realistic LLM span metadata.
                model_value = next(
                    (v["value"] for v in next(
                        bp["values"] for bp in demo_agent_config_blueprints if bp["env"] == env
                    ) if v["key"] == "model"),
                    "gpt-4o-mini",
                )

                experiment = client.create_experiment(
                    name=exp_cfg["name"],
                    dataset_name=suite_dataset_name,
                    evaluation_method="evaluation_suite",
                    tags=[env],
                    project_name=project_name,
                    experiment_config={
                        "agent_configuration": {
                            "_blueprint_id": blueprint["id"],
                            "blueprint_version": blueprint["name"],
                        }
                    },
                )

                # For each suite item: one trace (with full span tree), one experiment_item,
                # per-item + global assertion scores.
                traces_to_create = []
                spans_to_create = []
                experiment_refs = []
                score_items = []

                for i, template in enumerate(demo_test_suite_items):
                    trace_id = str(uuid6.uuid7())
                    dataset_item_id = item_id_by_question[template["question"]]

                    # Per-env answer: prod may have a weaker variant that legitimately
                    # fails the assertions marked False in pass_mask_prod; staging
                    # always uses the "good" answer that satisfies every assertion.
                    # Items without an answer_prod override use the same answer in both.
                    if env == "prod":
                        answer = template.get("answer_prod") or template["answer_staging"]
                    else:
                        answer = template["answer_staging"]

                    # Spread traces across the last ~30 minutes in reverse order.
                    start = now - datetime.timedelta(minutes=len(demo_test_suite_items) - i)
                    end = start + datetime.timedelta(seconds=random.randint(4, 12))

                    traces_to_create.append(TraceWrite(
                        id=trace_id,
                        name="handle_query",
                        project_name=project_name,
                        start_time=start,
                        end_time=end,
                        input={"question": template["question"]},
                        output={"answer": answer},
                        tags=[env],
                        source="experiment",
                    ))

                    spans_to_create.extend(_build_suite_span_tree(
                        trace_id=trace_id,
                        question=template["question"],
                        answer=answer,
                        trace_start=start,
                        trace_end=end,
                        project_name=project_name,
                        model=model_value,
                        env=env,
                    ))

                    experiment_refs.append(RestExperimentItem(
                        experiment_id=experiment.id,
                        dataset_item_id=dataset_item_id,
                        trace_id=trace_id,
                        # Pin project_name so the backend resolves project_id
                        # deterministically via projectService.retrieveByNamesOrCreate
                        # instead of falling back to a trace lookup — that lookup
                        # races with ClickHouse ingestion of the traces we just
                        # POSTed and can return null in prod.
                        project_name=project_name,
                    ))

                    # Assertion scores. Pass mode drives fail patterns. Name = full
                    # assertion text (matching real LLM-judge output after
                    # scoreNameMapping), reason = why it passed or failed.
                    pass_mode = exp_cfg["pass_mode"]
                    assertions = template["assertions"]
                    mask = (template["pass_mask_prod"]
                            if pass_mode == "prod"
                            else [True] * len(assertions))
                    fail_reasons = (template.get("fail_reasons_prod") or {}) if pass_mode == "prod" else {}

                    # Global suite-level assertion — always passes in the demo.
                    score_items.append(FeedbackScoreBatchItem(
                        id=trace_id,
                        project_name=project_name,
                        name=demo_test_suite_global_assertion,
                        category_name="suite_assertion",
                        value=1.0,
                        reason="The response is written entirely in clear, fluent English.",
                        source="online_scoring",
                    ))

                    for idx, (assertion_text, passed) in enumerate(zip(assertions, mask)):
                        if passed:
                            reason = (f"The response satisfies the assertion: "
                                      f"it clearly {assertion_text[0].lower() + assertion_text[1:]}.")
                        else:
                            reason = fail_reasons.get(
                                idx,
                                f"The response does not satisfy the assertion: "
                                f"it fails to {assertion_text[0].lower() + assertion_text[1:]}.",
                            )
                        score_items.append(FeedbackScoreBatchItem(
                            id=trace_id,
                            project_name=project_name,
                            name=assertion_text,
                            category_name="suite_assertion",
                            value=1.0 if passed else 0.0,
                            reason=reason,
                            source="online_scoring",
                        ))

                # Use synchronous REST for every write. The SDK's async streamer
                # (experiment.insert / client.flush) silently drops messages on
                # timeout in production — client.flush() returns True even when
                # it couldn't drain the queue before the signup handler exits,
                # which leaves the experiment with zero items in the UI even
                # though the "Seeded experiment" log fires. Posting directly to
                # the REST endpoint guarantees a 2xx-or-raise before we log.
                client.rest_client.traces.create_traces(traces=traces_to_create)
                client.rest_client.spans.create_spans(spans=spans_to_create)
                client.rest_client.experiments.create_experiment_items(
                    experiment_items=experiment_refs,
                )
                client.rest_client.traces.score_batch_of_traces(scores=score_items)

                logger.info("Seeded experiment '%s' with %d traces / %d spans / %d items / %d assertion scores",
                            exp_cfg["name"], len(traces_to_create), len(spans_to_create),
                            len(experiment_refs), len(score_items))

        except Exception as e:
            logger.error("Error creating demo test suite for workspace %s: %s",
                         workspace_name, e, exc_info=True)
        finally:
            if client:
                client.flush()
                client.end()


def create_demo_data(base_url: str, workspace_name, comet_api_key):
    with tracer.start_as_current_span("create_demo_data"):
        # Create a fresh context for this invocation to prevent race conditions
        # when multiple users sign up concurrently
        context = DemoDataContext()
        project_name = "Opik Demo Agent Observability"

        try:
            chatbot_seeded = create_demo_chatbot_project(context, base_url, workspace_name, comet_api_key)
            create_feedback_scores_definition(base_url, workspace_name, comet_api_key)
            create_default_environments(base_url, workspace_name, comet_api_key)
            # Only run the test-suite seed when the chatbot project was freshly created.
            # If the chatbot flow short-circuited on 409 (project already exists), the
            # suite/prompt/blueprints/experiments were seeded on the prior run too —
            # a second pass must be a no-op to stay idempotent under signup retries.
            if chatbot_seeded:
                create_demo_test_suite_and_experiments(base_url, workspace_name, comet_api_key, project_name)
            logger.info("Demo data created successfully")
        except Exception as e:
            logger.error(e)