"""Flask endpoints for Optimization Studio."""

import traceback
import logging
from uuid import uuid4

from flask import request, abort, Blueprint, Response, jsonify

from werkzeug.exceptions import HTTPException

from opik_backend.studio import (
    OptimizationCodeGenerator,
    OptimizationConfig,
    OptimizationJobContext,
)
from opik_backend.http_utils import build_error_response

logger = logging.getLogger(__name__)

studio = Blueprint("studio", __name__, url_prefix="/v1/private/studio")


@studio.errorhandler(400)
def bad_request(exception: HTTPException):
    return build_error_response(exception, 400)


@studio.errorhandler(500)
def internal_server_error(exception: HTTPException):
    return build_error_response(exception, 500)


@studio.route("/code", methods=["POST"])
def generate_code():
    """Generate Python code for optimization configuration.

    POST /v1/private/studio/code

    Accepts OptimizationStudioConfig format and returns generated Python code
    using the user download template (for_user_download=True).

    TEST: Local build verification - code updated successfully

    Request body:
        {
            "dataset_name": "dataset-name",
            "prompt": {"messages": [...]},
            "llm_model": {"model": "...", "parameters": {...}},
            "evaluation": {"metrics": [{"type": "...", "parameters": {...}}]},
            "optimizer": {"type": "...", "parameters": {...}}
        }

    Returns:
        Plain text Python code with Content-Disposition header for download
    """
    if request.method != "POST":
        abort(405, "Method not allowed")

    try:
        max_request_size_bytes = 1 * 1024 * 1024  # 1 MB limit for JSON body
        if request.content_length is not None and request.content_length > max_request_size_bytes:
            abort(413, "Request body too large")

        config_dict = request.get_json(force=True)

        if not config_dict:
            abort(400, "Request body is required")

        # Convert to OptimizationConfig
        config = OptimizationConfig.from_dict(config_dict)

        # Create minimal context (optimization_id is placeholder for code generation)
        context = OptimizationJobContext(
            optimization_id=str(uuid4()),
            workspace_id="",  # Not needed for code generation
            workspace_name="",  # Not needed for code generation
            config=config_dict,
            opik_api_key=None,  # Not needed for code generation
        )

        # Generate code using user download template
        code = OptimizationCodeGenerator.generate(
            config, context, for_user_download=True
        )

        # Return as plain text with download headers
        response = Response(
            code,
            mimetype="text/plain",
            headers={"Content-Disposition": 'attachment; filename="optimization.py"'},
        )

        return response

    except KeyError as e:
        logger.error(f"Missing required field in config: {e}", exc_info=True)
        abort(400, f"Missing required field: {e}")
    except ValueError as e:
        logger.error(f"Invalid configuration: {e}", exc_info=True)
        abort(400, f"Invalid configuration: {e}")
    except Exception as e:
        error_traceback = traceback.format_exc()
        error_msg = f"{type(e).__name__}: {str(e)}"
        logger.error(
            f"Error generating code: {error_msg}\n{error_traceback}", exc_info=True
        )
        # Return generic error response without exposing internal details
        return jsonify({"error": "Internal server error"}), 500
