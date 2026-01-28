"""Flask endpoints for Optimization Studio."""

import logging
import time
from uuid6 import uuid7

from flask import request, abort, Blueprint, Response, jsonify

from werkzeug.exceptions import HTTPException
from opentelemetry.metrics import get_meter

from opik_backend.studio import (
    OptimizationCodeGenerator,
    OptimizationConfig,
    OptimizationJobContext,
)
from opik_backend.studio.exceptions import (
    OptimizationError,
    InvalidOptimizerError,
    InvalidMetricError,
)
from opik_backend.http_utils import build_error_response

logger = logging.getLogger(__name__)

# Metrics setup
meter = get_meter(__name__)
generate_code_latency_histogram = meter.create_histogram(
    name="studio.generate_code.latency",
    description="End-to-end latency for generate_code endpoint in milliseconds",
    unit="ms",
)
generate_code_failure_counter = meter.create_counter(
    name="studio.generate_code.failures",
    description="Total number of generate_code endpoint failures",
    unit="1",
)

# Maximum request body size in bytes (1 MB)
MAX_REQUEST_SIZE_BYTES = 1 * 1024 * 1024

studio = Blueprint("studio", __name__, url_prefix="/v1/private/studio")


@studio.errorhandler(400)
def bad_request(exception: HTTPException):
    return build_error_response(exception, 400)


@studio.errorhandler(500)
def internal_server_error(exception: HTTPException):
    return build_error_response(exception, 500)


@studio.errorhandler(KeyError)
def handle_key_error(exception: KeyError):
    """Handle KeyError exceptions (missing required fields)."""
    logger.error(f"Missing required field in config: {exception}", exc_info=True)
    return jsonify({"error": f"Missing required field: {exception}"}), 400


@studio.errorhandler(ValueError)
def handle_value_error(exception: ValueError):
    """Handle ValueError exceptions (invalid configuration)."""
    logger.error(f"Invalid configuration: {exception}", exc_info=True)
    return jsonify({"error": f"Invalid configuration: {exception}"}), 400


@studio.errorhandler(InvalidOptimizerError)
def handle_invalid_optimizer_error(exception: InvalidOptimizerError):
    """Handle InvalidOptimizerError exceptions (invalid optimizer type or configuration)."""
    logger.error(f"Invalid optimizer: {exception}", exc_info=True)
    return jsonify({"error": str(exception)}), 400


@studio.errorhandler(InvalidMetricError)
def handle_invalid_metric_error(exception: InvalidMetricError):
    """Handle InvalidMetricError exceptions (invalid metric type or configuration)."""
    logger.error(f"Invalid metric: {exception}", exc_info=True)
    return jsonify({"error": str(exception)}), 400


@studio.errorhandler(OptimizationError)
def handle_optimization_error(exception: OptimizationError):
    """Handle OptimizationError exceptions (invalid configuration or optimization errors)."""
    logger.error(f"Optimization error: {exception}", exc_info=True)
    return jsonify({"error": str(exception)}), 400


@studio.errorhandler(Exception)
def handle_generic_exception(exception: Exception):
    """Handle all other exceptions."""
    error_msg = f"{type(exception).__name__}: {str(exception)}"
    logger.error(f"Error generating code: {error_msg}", exc_info=True)
    # Return generic error response without exposing internal details
    return jsonify({"error": "Internal server error"}), 500


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
        JSON response with code field and Content-Disposition header for download:
        {
            "code": "generated Python code string"
        }
    """
    start_time = time.time()
    route = "/v1/private/studio/code"
    status = "success"
    status_code = 200
    error_type = None

    try:
        if request.method != "POST":
            abort(405, "Method not allowed")

        if (
            request.content_length is not None
            and request.content_length > MAX_REQUEST_SIZE_BYTES
        ):
            abort(413, "Request body too large")

        config_dict = request.get_json(force=True)

        if not isinstance(config_dict, dict) or not config_dict:
            abort(400, "Request body must be a non-empty JSON object")

        # Convert to OptimizationConfig
        config = OptimizationConfig.from_dict(config_dict)

        # Create minimal context (optimization_id is placeholder for code generation)
        context = OptimizationJobContext(
            optimization_id=str(uuid7()),
            workspace_id="",  # Not needed for code generation
            workspace_name="",  # Not needed for code generation
            config=config_dict,
            opik_api_key=None,  # Not needed for code generation
        )

        # Generate code using user download template
        code = OptimizationCodeGenerator.generate(
            config, context, for_user_download=True
        )

        # Return as JSON with download headers
        response = jsonify({"code": code})
        response.headers["Content-Disposition"] = (
            'attachment; filename="optimization.py"'
        )
        return response

    except HTTPException as e:
        # Handle Flask abort() calls and HTTP exceptions
        status = "error"
        status_code = e.code
        error_type = type(e).__name__
        error_msg = str(e)

        logger.error(
            f"HTTP error in generate_code: {error_type} (status={status_code}): {error_msg}",
            exc_info=True,
        )

        # Increment failure counter with structured labels
        generate_code_failure_counter.add(
            1,
            {
                "route": route,
                "error_type": error_type,
                "status_code": str(status_code),
            },
        )

        # Re-raise to let Flask error handlers process it
        raise

    except (
        KeyError,
        ValueError,
        InvalidOptimizerError,
        InvalidMetricError,
        OptimizationError,
    ) as e:
        # Handle exceptions that have Flask error handlers registered (they return 400)
        status = "error"
        status_code = 400
        error_type = type(e).__name__
        error_msg = str(e)

        logger.error(
            f"Validation error in generate_code: {error_type}: {error_msg}",
            exc_info=True,
        )

        # Increment failure counter with structured labels
        generate_code_failure_counter.add(
            1,
            {
                "route": route,
                "error_type": error_type,
                "status_code": str(status_code),
            },
        )

        # Re-raise to let Flask error handlers process it
        raise

    except Exception as e:
        # Handle all other exceptions (unhandled exceptions -> 500)
        status = "error"
        status_code = 500
        error_type = type(e).__name__
        error_msg = str(e)

        logger.error(
            f"Error in generate_code: {error_type}: {error_msg}",
            exc_info=True,
        )

        # Increment failure counter with structured labels
        generate_code_failure_counter.add(
            1,
            {
                "route": route,
                "error_type": error_type,
                "status_code": str(status_code),
            },
        )

        # Re-raise to let Flask error handlers process it (will return safe generic 500)
        raise

    finally:
        # Always record latency histogram
        latency_ms = (time.time() - start_time) * 1000
        generate_code_latency_histogram.record(
            latency_ms,
            {
                "route": route,
                "status": status,
            },
        )
