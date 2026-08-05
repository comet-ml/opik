"""Span-related endpoints for test helper service"""

from flask import Blueprint, request
from .utils import (
    get_opik_client,
    get_opik_api_client,
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


@spans_bp.route("/delete-by-project", methods=["DELETE"])
def delete_spans_by_project():
    """Spans have no working single- or bulk-delete endpoint of their own
    (SpansResource.deleteById is an unimplemented 501 stub), so leftover spans
    are purged by deleting their parent traces instead — trace deletion cascades
    to spans asynchronously via TraceDeletedListener."""
    data = request.get_json()
    validate_required_fields(data, ["project_name"])

    project_name = data["project_name"]
    max_results = data.get("max_results", 1000)

    client = get_opik_client()
    api_client = get_opik_api_client()

    deleted_count = 0
    while True:
        spans = client.search_spans(project_name=project_name, max_results=max_results, truncate=True)
        if not spans:
            break
        trace_ids = {span.trace_id for span in spans}
        api_client.traces.delete_traces(ids=list(trace_ids))
        deleted_count += len(spans)

    return success_response({"deleted_count": deleted_count})
