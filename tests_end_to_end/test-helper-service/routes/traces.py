"""Trace-related endpoints for test helper service"""

from flask import Blueprint, request, abort
from werkzeug.exceptions import HTTPException
import os
import time
from opik import opik_context, track, Attachment
from .utils import (
    get_opik_client,
    get_opik_api_client,
    build_error_response,
    success_response,
    validate_required_fields,
)

traces_bp = Blueprint("traces", __name__)


def resolve_attachment_path(relative_path: str) -> str:
    """Resolve attachment path relative to tests_end_to_end directory."""
    current_dir = os.path.dirname(os.path.abspath(__file__))
    # routes/ -> test-helper-service/ -> tests_end_to_end/
    tests_end_to_end_dir = os.path.join(current_dir, "..", "..")
    full_path = os.path.join(tests_end_to_end_dir, relative_path)
    return os.path.abspath(full_path)


@traces_bp.errorhandler(400)
def bad_request(exception: HTTPException):
    return build_error_response(exception, 400)


@traces_bp.errorhandler(408)
def request_timeout(exception: HTTPException):
    return build_error_response(exception, 408)


@traces_bp.errorhandler(500)
def internal_server_error(exception: HTTPException):
    return build_error_response(exception, 500)


@traces_bp.route("/create-traces-decorator", methods=["POST"])
def create_traces_decorator():
    data = request.get_json()
    validate_required_fields(data, ["project_name"])

    project_name = data["project_name"]
    traces_number = data.get("traces_number", 10)
    prefix = data.get("prefix", "test-trace-")

    @track(project_name=project_name)
    def f2(input_str: str):
        return "test output"

    @track(project_name=project_name)
    def f1(input_str: str, trace_name: str):
        opik_context.update_current_trace(name=trace_name)
        return f2(input_str)

    for i in range(traces_number):
        f1("test input", f"{prefix}{i}")

    return success_response({"traces_created": traces_number})


@traces_bp.route("/create-traces-client", methods=["POST"])
def create_traces_client():
    data = request.get_json()
    validate_required_fields(data, ["project_name"])

    project_name = data["project_name"]
    traces_number = data.get("traces_number", 10)
    prefix = data.get("prefix", "test-trace-")
    client = get_opik_client()

    for i in range(traces_number):
        client_trace = client.trace(
            name=f"{prefix}{i}",
            project_name=project_name,
            input={"input": "test input"},
            output={"output": "test output"},
        )
        _ = client_trace.span(
            name="span", input={"input": "test input"}, output={"output": "test output"}
        )

    return success_response({"traces_created": traces_number})


@traces_bp.route("/create-traces-with-spans-client", methods=["POST"])
def create_traces_with_spans_client():
    data = request.get_json()
    validate_required_fields(data, ["project_name", "trace_config", "span_config"])

    project_name = data["project_name"]
    trace_config = data["trace_config"]
    span_config = data["span_config"]
    client = get_opik_client()

    for trace_index in range(trace_config["count"]):
        client_trace = client.trace(
            name=trace_config["prefix"] + str(trace_index),
            input={"input": f"input-{trace_index}"},
            output={"output": f"output-{trace_index}"},
            tags=trace_config.get("tags", []),
            metadata=trace_config.get("metadata", {}),
            feedback_scores=trace_config.get("feedback_scores", []),
            project_name=project_name,
        )

        for span_index in range(span_config["count"]):
            client_span = client_trace.span(
                name=span_config["prefix"] + str(span_index),
                input={"input": f"input-{span_index}"},
                output={"output": f"output-{span_index}"},
                tags=span_config.get("tags", []),
                metadata=span_config.get("metadata", {}),
            )

            for score in span_config.get("feedback_scores", []):
                client_span.log_feedback_score(name=score["name"], value=score["value"])

    return success_response({"traces_created": trace_config["count"]})


@traces_bp.route("/create-traces-with-spans-decorator", methods=["POST"])
def create_traces_with_spans_decorator():
    data = request.get_json()
    validate_required_fields(data, ["project_name", "trace_config", "span_config"])

    project_name = data["project_name"]
    trace_config = data["trace_config"]
    span_config = data["span_config"]

    @track(project_name=project_name)
    def make_span(x):
        opik_context.update_current_span(
            name=span_config["prefix"] + str(x),
            input={"input": f"input-{x}"},
            metadata=span_config.get("metadata", {}),
            tags=span_config.get("tags", []),
            feedback_scores=span_config.get("feedback_scores", []),
        )
        return {"output": f"output-{x}"}

    @track(project_name=project_name)
    def make_trace(x):
        for spans_no in range(span_config["count"]):
            make_span(spans_no)

        opik_context.update_current_trace(
            name=trace_config["prefix"] + str(x),
            input={"input": f"input-{x}"},
            metadata=trace_config.get("metadata", {}),
            tags=trace_config.get("tags", []),
            feedback_scores=trace_config.get("feedback_scores", []),
        )
        return {"output": f"output-{x}"}

    for x in range(trace_config["count"]):
        make_trace(x)

    return success_response({"traces_created": trace_config["count"]})


@traces_bp.route("/create-trace-with-attachment-client", methods=["POST"])
def create_trace_with_attachment_client():
    data = request.get_json()
    validate_required_fields(data, ["project_name", "attachment_path"])

    project_name = data["project_name"]
    attachment_path = resolve_attachment_path(data["attachment_path"])
    client = get_opik_client()

    client.trace(
        name="trace_with_attachment",
        input={"instruction": "Analyze the document, ..."},
        project_name=project_name,
        attachments=[Attachment(data=attachment_path)],
    )

    return success_response({"attachment_name": os.path.basename(attachment_path)})


@traces_bp.route("/create-trace-with-attachment-decorator", methods=["POST"])
def create_trace_with_attachment_decorator():
    data = request.get_json()
    validate_required_fields(data, ["project_name", "attachment_path"])

    project_name = data["project_name"]
    attachment_path = resolve_attachment_path(data["attachment_path"])

    @track(project_name=project_name)
    def log_attachment():
        opik_context.update_current_trace(
            attachments=[Attachment(data=attachment_path)]
        )

    log_attachment()

    return success_response({"attachment_name": os.path.basename(attachment_path)})


@traces_bp.route("/create-trace-with-span-attachment", methods=["POST"])
def create_trace_with_span_attachment():
    data = request.get_json()
    validate_required_fields(data, ["project_name", "attachment_path"])

    project_name = data["project_name"]
    attachment_path = resolve_attachment_path(data["attachment_path"])
    client = get_opik_client()

    trace = client.trace(
        name="my_trace",
        project_name=project_name,
        input={"user_question": "Hello, how are you?"},
        output={"response": "Comment ça va?"},
    )

    span_name = "Add prompt template"
    trace.span(
        name=span_name,
        input={
            "text": "Hello, how are you?",
            "prompt_template": "Translate the following text to French: {text}",
        },
        output={"text": "Translate the following text to French: hello, how are you?"},
        attachments=[Attachment(data=attachment_path)],
    )

    return success_response(
        {"attachment_name": os.path.basename(attachment_path), "span_name": span_name}
    )


@traces_bp.route("/get-traces", methods=["POST"])
def get_traces():
    data = request.get_json()
    validate_required_fields(data, ["project_name"])

    project_name = data["project_name"]
    size = data.get("size", 10)
    client = get_opik_api_client()

    traces_response = client.traces.get_traces_by_project(
        project_name=project_name, size=size
    )

    return success_response({"traces": traces_response.dict()["content"]})


@traces_bp.route("/delete-traces", methods=["DELETE"])
def delete_traces():
    data = request.get_json()
    trace_ids = data.get("trace_ids", [])

    if not trace_ids:
        abort(400, "trace_ids are required")

    client = get_opik_api_client()
    client.traces.delete_traces(ids=trace_ids)

    return success_response({"deleted_count": len(trace_ids)})


@traces_bp.route("/wait-for-traces-visible", methods=["POST"])
def wait_for_traces_visible():
    data = request.get_json()
    validate_required_fields(data, ["project_name"])

    project_name = data["project_name"]
    expected_count = data.get("expected_count", 1)
    timeout = data.get("timeout", 30)
    client = get_opik_api_client()

    start_time = time.time()
    delay = 1

    while time.time() - start_time < timeout:
        traces_response = client.traces.get_traces_by_project(
            project_name=project_name, size=expected_count
        )
        traces = traces_response.dict()["content"]

        if len(traces) >= expected_count:
            return success_response({"message": f"Found {len(traces)} traces"})

        time.sleep(delay)
        delay = min(delay * 2, timeout - (time.time() - start_time))

    abort(408, f"Traces not visible within {timeout} seconds")
