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
from opik_backend.demo_data import demo_traces, demo_spans, demo_thread_feedback_scores
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


def project_exists(base_url, workspace_name, comet_api_key, project_name):
    request = {
        "url": "/v1/private/projects/retrieve",
        "method": "POST",
        "payload": {
            "name": project_name,
        },
    }

    _, status_code = make_http_request(base_url, request, workspace_name, comet_api_key)
    return status_code == 200

def api_key_ready(base_url, workspace_name, comet_api_key):
    request = {
        "url": "/v1/private/projects",
        "method": "GET",
    }
    _, status_code = make_http_request(base_url, request, workspace_name, comet_api_key)
    return status_code == 200

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

def process_traces_with_time_shift(traces, context: DemoDataContext, client: opik.Opik):
    """
    Process traces with proper time shifts and time-based UUIDs.
    
    Shifts all trace timestamps to present time while preserving temporal relationships,
    and generates time-based UUIDs for consistent ordering.
    
    Calculates time shift from the latest end_time across traces only
    to ensure all data is brought to present while preserving time distances.
    
    Parameters:
    - traces: List of trace dictionaries to process
    - context: DemoDataContext object holding state
    - client: opik.Opik client for logging traces
    
    Returns:
    - datetime.timedelta: The time shift applied to all data (to be used for spans)
    """
    # Calculate time shift from traces only to move latest trace end_time to now
    # This same shift will be applied to spans to preserve all time distances
    time_shift = calculate_time_shift_to_now(traces)
    
    for idx, original_trace in enumerate(sorted(traces, key=lambda x: x["id"])):
        # Create a copy to avoid mutating the original demo_data
        trace = dict(original_trace)
        # Store the old ID before modification
        old_trace_id = trace["id"]
        # Apply time shift to maintain time differences
        trace["start_time"] = apply_time_shift(trace["start_time"], time_shift)
        trace["end_time"] = apply_time_shift(trace["end_time"], time_shift)
        new_id = get_new_uuid_by_time(context, old_trace_id, trace["start_time"])
        trace["id"] = new_id
        # Remove fields that shouldn't be in the trace data
        trace.pop("project_id", None)
        trace.pop("workspace_id", None)
        set_time_shift(context, new_id, time_shift)
        client.trace(**trace)
    
    return time_shift

def process_spans_with_time_shift(spans, time_shift, context: DemoDataContext, client: opik.Opik):
    """
    Process spans with the same time shift as their parent traces.
    
    Spans use the time shift passed from trace processing to maintain relative timing.
    
    First pass generates all time-based UUIDs and stores time shifts.
    Second pass logs all spans with properly shifted times and parent_span_id mappings.
    
    Parameters:
    - spans: List of span dictionaries to process
    - time_shift: The time shift to apply (from traces)
    - context: DemoDataContext object holding state
    - client: opik.Opik client for logging spans
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

    # Second pass: Log all spans with properly shifted times and parent_span_id mappings
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
        # Remove fields that shouldn't be in the span data
        span.pop("project_id", None)
        span.pop("workspace_id", None)
        client.span(**span)

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
    with tracer.start_as_current_span("create_demo_chatbot_project"):
        client: opik.Opik = None

        try:
            project_name = "Opik Demo Agent Observability"

            # Short-circuit if project already exists (also implicitly validates the API key)
            if project_exists(base_url, workspace_name, comet_api_key, project_name):
                logger.info("%s project already exists", project_name)
                return

            # If project doesn't exist, it could be because the API key isn't ready yet.
            # Wait for API key to be ready (exponential backoff: 0.5s, 1s, 2s, 4s, 8s ≈ 15.5s total)
            max_retries = 5
            for attempt in range(max_retries):
                if api_key_ready(base_url, workspace_name, comet_api_key):
                    break
                if attempt == max_retries - 1:
                    logger.error("API key not ready for workspace %s after %d retries, aborting demo data creation", workspace_name, max_retries)
                    return
                time.sleep(0.5 * (2 ** attempt))

            client = opik.Opik(
                project_name=project_name,
                workspace=workspace_name,
                host=base_url,
                api_key=comet_api_key,
                _use_batching=True,
            )

            # Extract unique thread IDs before processing traces
            threads = list({trace["thread_id"] for trace in demo_traces if "thread_id" in trace and trace["thread_id"] is not None})
            
            time_shift = process_traces_with_time_shift(demo_traces, context, client)
            process_spans_with_time_shift(demo_spans, time_shift, context, client)
            flush_result = client.flush()
            if not flush_result:
                logger.error("Failed to flush demo traces for workspace %s project %s, aborting", workspace_name, project_name)
                return

            # Wait for project to be queryable (exponential backoff: 0.5s, 1s, 2s, 4s, 8s ≈ 15.5s total)
            for attempt in range(max_retries):
                if project_exists(base_url, workspace_name, comet_api_key, project_name):
                    break
                if attempt == max_retries - 1:
                    logger.error("Project %s not found for workspace %s after %d retries, skipping thread/feedback operations", project_name, workspace_name, max_retries)
                    return
                time.sleep(0.5 * (2 ** attempt))

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
        except Exception as e:
            logger.error("Error creating demo chatbot project for workspace %s: %s", workspace_name, e)
        finally:
            # Close the client
            if client:
                client.flush()
                client.end()

def create_demo_data(base_url: str, workspace_name, comet_api_key):
    with tracer.start_as_current_span("create_demo_data"):
        # Create a fresh context for this invocation to prevent race conditions
        # when multiple users sign up concurrently
        context = DemoDataContext()
        
        try:
            create_demo_chatbot_project(context, base_url, workspace_name, comet_api_key)
            create_feedback_scores_definition(base_url, workspace_name, comet_api_key)
            logger.info("Demo data created successfully")
        except Exception as e:
            logger.error(e)