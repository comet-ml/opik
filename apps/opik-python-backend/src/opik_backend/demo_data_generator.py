# Setting up a demo project
#
# Evaluation traces & spans
# We start with evaluation so it shows up at the bottom.
# The evaluation is going to be tracked into a separate project from the demo traces.
# It was run using a simple context with 3 sentences, and 3 questions asking about it.

import opik
import json
import urllib.request
import uuid6
import logging
import datetime
import time
import uuid
import random
from concurrent.futures import ThreadPoolExecutor, as_completed

import opik.rest_api
from opik_backend.demo_data import demo_traces, demo_spans, demo_thread_feedback_scores, demo_projects, demo_prompt, experiment_traces_grouped_by_project, experiment_spans_grouped_by_project, demo_datasets, demo_dataset_items, demo_experiments, demo_experiment_items, demo_optimizations
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
    dataset_ids: dict = field(default_factory=dict)
    dataset_item_ids: dict = field(default_factory=dict)
    prompt_names: dict = field(default_factory=dict)
    prompt_commits: dict = field(default_factory=dict)


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

def create_demo_evaluation_project(context: DemoDataContext, base_url: str, workspace_name, comet_api_key):
    with tracer.start_as_current_span("create_demo_evaluation_project"):
        client: opik.Opik = None
        try:

            project_name = "Opik Demo Assistant"

            # Find project ID by project name
            project_id = next((pid for pid, pname in demo_projects.items() if pname == "Opik Assistant"), None)
           
            if project_id is None:
                logger.error("Could not find project ID for 'Opik Assistant'")
                return
            
            if project_exists(base_url, workspace_name, comet_api_key, project_name):
                logger.info("%s project already exists", project_name)
                return

            client = opik.Opik(
                project_name=project_name,
                workspace=workspace_name,
                host=base_url,
                api_key=comet_api_key,
                _use_batching=True,
            )

            evaluation_traces = experiment_traces_grouped_by_project[project_id]
            evaluation_spans = experiment_spans_grouped_by_project[project_id]
            time_shift = process_traces_with_time_shift(evaluation_traces, context, client)
            process_spans_with_time_shift(evaluation_spans, time_shift, context, client)
            client.flush()
            
            # Prompts
            # We now create 3 versions of a Q&A prompt. The final version is from llama-index.
            for version in demo_prompt["versions"]:
                prompt = client.create_prompt(
                    name="Demo - " + demo_prompt["name"],
                    prompt=version["template"],
                )
                context.prompt_names[version["id"]] = prompt.name
                context.prompt_commits[version["id"]] = prompt.commit

            # Dataset
            dataset_name = "Opik Questions"
            dataset = next((x for x in demo_datasets if x["name"] == dataset_name), None)
            opik_dataset = client.get_or_create_dataset(name="Opik Demo Questions", description=dataset["description"])
            
            context.dataset_ids[dataset["id"]] = opik_dataset.id

            dataset_item_ids = {}
            new_items = []
            for item in demo_dataset_items[dataset["id"]]:
                new_id = str(uuid6.uuid7())
                dataset_item_ids[item["id"]] = new_id
                context.dataset_item_ids[item["id"]] = new_id
                new_item = {
                    **item["data"],
                    "id": new_id,
                }
                new_items.append(new_item)

            opik_dataset.insert(new_items)

            # In addition to creating the dataset, we also create a mapping from the dataset items to the traces. This will be handy for creating the experiment.
            items = opik_dataset.get_items()

            # Experiment
            # The experiment is constructed by joining the traces with the dataset items.
            experiments = [x for x in demo_experiments if x["name"] in ["opik-assistant-v1", "opik-assistant-v2"]]

            for item in experiments:
                commits =  [sub_item.decode() for sublist in item["prompt_versions"].values() for sub_item in sublist]
                
                prompts = []
                for version in commits:
                    prompt = client.get_prompt(name=context.prompt_names[version], commit=context.prompt_commits[version])
                    prompts.append(prompt)
                
                experiment = client.create_experiment(name="Demo-" + item["name"], dataset_name=opik_dataset.name, prompts=prompts)
                experiment_items = []
                
                for experiment_item in demo_experiment_items[item["id"]]:
                    trace_id = get_new_uuid(context, experiment_item["trace_id"])
                    dataset_item_id = dataset_item_ids[experiment_item["dataset_item_id"]]
                    
                    if dataset_item_id is not None:
                        experiment_items.append(
                            opik.api_objects.experiment.experiment_item.ExperimentItemReferences(
                                dataset_item_id=dataset_item_id, trace_id=trace_id
                            )
                        )

                experiment.insert(experiment_items)

        except Exception as e:
            logger.error(e)
        finally:
            # Close the client
            if client:
                client.flush()
                client.end()

def create_demo_chatbot_project(context: DemoDataContext, base_url: str, workspace_name, comet_api_key):
    with tracer.start_as_current_span("create_demo_chatbot_project"):
        client: opik.Opik = None

        try:
            project_name = "Opik Demo Agent Observability"
            if project_exists(base_url, workspace_name, comet_api_key, project_name):
                logger.info("%s project already exists", project_name)
                return

            # Demo traces and spans
            # We have a simple chatbot application built using llama-index.
            # We gave it the content of Opik documentation as context, and then asked it a few questions.

            client = opik.Opik(
                project_name=project_name,
                workspace=workspace_name,
                host=base_url,
                api_key=comet_api_key,
                _use_batching=True,
            )

            # Extract thread IDs before processing traces
            threads = [trace["thread_id"] for trace in demo_traces if "thread_id" in trace and trace["thread_id"] is not None]
            
            time_shift = process_traces_with_time_shift(demo_traces, context, client)
            process_spans_with_time_shift(demo_spans, time_shift, context, client)
            client.flush()


            done = False
            max_attempts = 10
            attempts = 0

            while not done and attempts < max_attempts:
                try:
                    client.rest_client.traces.close_trace_thread(project_name=project_name, thread_ids=threads)
                    done = True
                    attempts = 0
                except Exception as e:
                    logger.error(f"Error closing threads {threads} attempt {attempts}: {e}")
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
                        logger.error(f"Error scoring batch of threads attempt {attempts}: {e}")
                        attempts += 1
                        time.sleep(0.5)
                if not done:
                    logger.error("Failed to score batch of threads after %d attempts", max_attempts)
        except Exception as e:
            logger.error(e)
        finally:
            # Close the client
            if client:
                client.flush()
                client.end()

def create_demo_optimizer_project(context: DemoDataContext, base_url: str, workspace_name, comet_api_key):
    with tracer.start_as_current_span("create_demo_optimizer_project"):
        client: opik.Opik = None
        try:

            project_name = "Opik Demo Optimizer"

            # Find project ID by project name
            project_id = next((pid for pid, pname in demo_projects.items() if pname == "Opik Optimizer"), None)
           
            if project_id is None:
                logger.error("Could not find project ID for 'Opik Optimizer'")
                return
            
            if project_exists(base_url, workspace_name, comet_api_key, project_name):
                logger.info("%s project already exists", project_name)
                return

            client = opik.Opik(
                project_name=project_name,
                workspace=workspace_name,
                host=base_url,
                api_key=comet_api_key,
                _use_batching=True,
            )

            evaluation_traces = experiment_traces_grouped_by_project[project_id]
            evaluation_spans = experiment_spans_grouped_by_project[project_id]
            time_shift = process_traces_with_time_shift(evaluation_traces, context, client)
            process_spans_with_time_shift(evaluation_spans, time_shift, context, client)
            client.flush()

            dataset_name = "Opik Demo Questions"
            
            for optimization in demo_optimizations:

                new_optimization = client.create_optimization(
                    name=optimization["name"],
                    dataset_name=dataset_name,
                    objective_name=optimization["objective_name"],
                    metadata=optimization["metadata"],
                )
            
                # Experiment
                # The experiment is constructed by joining the traces with the dataset items.
                experiments = [x for x in demo_experiments if x.get("optimization_id") == optimization["id"]]

                for item in experiments:
                    commits =  [item.decode() for sublist in item.get("prompt_versions", {}).values() for item in sublist]
                    
                    prompts = []
                    for version in commits:
                        prompt = client.get_prompt(name=context.prompt_names[version], commit=context.prompt_commits[version])
                        prompts.append(prompt)
                    
                    experiment = client.create_experiment(
                        name="Demo-" + item["name"],
                        dataset_name=dataset_name,
                        prompts=prompts,
                        optimization_id=new_optimization.id,
                        experiment_config = {
                            **item.get("metadata", {}),
                            "dataset": dataset_name,
                        },
                        type=item["type"]
                    )

                    experiment_items = []
                    
                    for experiment_item in demo_experiment_items.get(item["id"], []):
                        trace_id = get_new_uuid(context, experiment_item["trace_id"])
                        dataset_item_id = context.dataset_item_ids[experiment_item["dataset_item_id"]]
                        
                        if dataset_item_id is not None:
                            experiment_items.append(
                                opik.api_objects.experiment.experiment_item.ExperimentItemReferences(
                                    dataset_item_id=dataset_item_id, trace_id=trace_id
                                )
                            )

                    experiment.insert(experiment_items)
                    
                client.rest_client.optimizations.update_optimizations_by_id(
                    id=new_optimization.id,
                    status="completed"
                )

        except Exception as e:
            logger.error(e)
        finally:
            # Close the client
            if client:
                client.flush()
                client.end()

def create_demo_playground_project(context: DemoDataContext, base_url: str, workspace_name, comet_api_key):
    with tracer.start_as_current_span("create_demo_playground_project"):
        try:
            project_name = "playground"
            if project_exists(base_url, workspace_name, comet_api_key, project_name):
                logger.info("%s project already exists", project_name)
                return

            request = {
                "url": "/v1/private/projects",
                "method": "POST",
                "payload": {
                    "name": project_name,
                },
            }

            _, status_code = make_http_request(base_url, request, workspace_name, comet_api_key)
            if status_code in [201, 200]:
                logger.info("%s project created successfully", project_name)
            else:
                logger.error("Failed to create playground project, status code: %s", status_code)
        except Exception as e:
            logger.error("Error creating playground project: %s", e)

def create_demo_data(base_url: str, workspace_name, comet_api_key):
    with tracer.start_as_current_span("create_demo_data"):
        # Create a fresh context for this invocation to prevent race conditions
        # when multiple users sign up concurrently
        context = DemoDataContext()
        
        try:
            create_demo_evaluation_project(context, base_url, workspace_name, comet_api_key)
            create_demo_optimizer_project(context, base_url, workspace_name, comet_api_key)
            create_demo_chatbot_project(context, base_url, workspace_name, comet_api_key)
            create_demo_playground_project(context, base_url, workspace_name, comet_api_key)
            create_feedback_scores_definition(base_url, workspace_name, comet_api_key)
            logger.info("Demo data created successfully")
        except Exception as e:
            logger.error(e)