"""Thread/Conversation-related endpoints for test helper service"""

from flask import Blueprint, request
from werkzeug.exceptions import HTTPException
from opik import opik_context, track
from .utils import (
    get_opik_client,
    build_error_response,
    success_response,
    validate_required_fields,
)

threads_bp = Blueprint("threads", __name__)


@threads_bp.errorhandler(400)
def bad_request(exception: HTTPException):
    return build_error_response(exception, 400)


@threads_bp.errorhandler(500)
def internal_server_error(exception: HTTPException):
    return build_error_response(exception, 500)


@threads_bp.route("/create-threads-decorator", methods=["POST"])
def create_threads_decorator():
    data = request.get_json()
    validate_required_fields(data, ["project_name", "thread_configs"])

    project_name = data["project_name"]
    thread_configs = data["thread_configs"]

    response_map = {}
    for thread in thread_configs:
        response_map[thread["thread_id"]] = dict(
            zip(thread["inputs"], thread["outputs"])
        )

    @track(project_name=project_name)
    def chat_message(input_msg, thread_id):
        opik_context.update_current_trace(thread_id=thread_id)
        return response_map[thread_id][input_msg]

    for thread in thread_configs:
        for input_msg in thread["inputs"]:
            chat_message(input_msg, thread["thread_id"])

    return success_response(
        {"threads_created": len(thread_configs), "thread_configs": thread_configs}
    )


@threads_bp.route("/create-threads-client", methods=["POST"])
def create_threads_client():
    data = request.get_json()
    validate_required_fields(data, ["project_name", "thread_configs"])

    project_name = data["project_name"]
    thread_configs = data["thread_configs"]
    client = get_opik_client()

    for thread in thread_configs:
        for input_msg, output_msg in zip(thread["inputs"], thread["outputs"]):
            client.trace(
                name="chat_conversation",
                input=input_msg,
                output=output_msg,
                thread_id=thread["thread_id"],
                project_name=project_name,
            )

    return success_response(
        {"threads_created": len(thread_configs), "thread_configs": thread_configs}
    )
