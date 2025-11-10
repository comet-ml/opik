"""Common utilities for test helper service routes"""

from flask import jsonify, abort
from werkzeug.exceptions import HTTPException
import os
from opik import Opik
from opik.rest_api.client import OpikApi


def get_opik_client():
    """Get configured Opik SDK client"""
    return Opik(
        api_key=os.getenv("OPIK_API_KEY", None),
        workspace=os.getenv("OPIK_WORKSPACE", None),
        host=os.getenv("OPIK_URL_OVERRIDE", None),
    )


def get_opik_api_client():
    """Get configured Opik REST API client"""
    return OpikApi(
        base_url=os.getenv("OPIK_URL_OVERRIDE", None),
        workspace_name=os.getenv("OPIK_WORKSPACE", None),
        api_key=os.getenv("OPIK_API_KEY", None),
    )


def build_error_response(exception: HTTPException, status_code: int):
    """Build standardized error response"""
    return jsonify(
        {"success": False, "error": str(exception), "type": type(exception).__name__}
    ), status_code


def success_response(data: dict, status_code: int = 200):
    """Create standardized success response"""
    return jsonify({"success": True, **data}), status_code


def validate_required_fields(data: dict, required_fields: list[str]):
    """Validate that required fields are present in request data"""
    missing = [field for field in required_fields if not data.get(field)]
    if missing:
        abort(400, f"Missing required fields: {', '.join(missing)}")
