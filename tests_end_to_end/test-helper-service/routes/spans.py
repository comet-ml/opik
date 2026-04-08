"""Span-related endpoints for test helper service"""

from flask import Blueprint, request
from .utils import (
    get_opik_client,
    success_response,
    validate_required_fields,
)

spans_bp = Blueprint("spans", __name__)


@spans_bp.route("/search-spans", methods=["POST"])
def search_spans():
    data = request.get_json()
    validate_required_fields(data, ["project_name"])

    project_name = data["project_name"]
    max_results = data.get("max_results", 1000)
    truncate = data.get("truncate", True)
    exclude = data.get("exclude", None)
    filter_string = data.get("filter_string", None)
    wait_for_at_least = data.get("wait_for_at_least", None)
    wait_for_timeout = data.get("wait_for_timeout", 30)
    client = get_opik_client()

    spans = client.search_spans(
        project_name=project_name,
        max_results=max_results,
        truncate=truncate,
        exclude=exclude,
        filter_string=filter_string,
        wait_for_at_least=wait_for_at_least,
        wait_for_timeout=wait_for_timeout,
    )

    return success_response({"spans": [s.dict() for s in spans]})
