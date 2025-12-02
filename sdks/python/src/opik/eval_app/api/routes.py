"""API routes for the eval app."""

import logging

import pydantic
import dataclasses
from fastapi import APIRouter, Request, status
from fastapi.responses import JSONResponse

from .. import exceptions
from .. import schemas
from ..services import EvalService, create_service

LOGGER = logging.getLogger(__name__)

evaluation_router = APIRouter(prefix="/api/v1/evaluation", tags=["evaluation"])
healthcheck_router = APIRouter(tags=["healthcheck"])

_service_instance: EvalService | None = None


def _get_service() -> EvalService:
    """Get or create the eval service instance."""
    global _service_instance
    if _service_instance is None:
        _service_instance = create_service()
    return _service_instance


@evaluation_router.get("/metrics", response_model=schemas.MetricsListResponse)
async def list_metrics() -> schemas.MetricsListResponse:
    """
    List all supported metrics with their descriptors.

    Returns information about available metrics including:
    - Metric name and description
    - Parameters for __init__ (to configure the metric)
    - Parameters for score() (data to provide for evaluation)
    """
    service = _get_service()
    metrics_list = service.list_metrics()

    return schemas.MetricsListResponse(
        metrics=[
            schemas.MetricDescriptorResponse(
                name=metric.name,
                description=metric.description,
                init_params=[
                    schemas.MetricParamDescriptor(**dataclasses.asdict(p))
                    for p in metric.init_params
                ],
                score_params=[
                    schemas.MetricParamDescriptor(**dataclasses.asdict(p))
                    for p in metric.score_params
                ],
            )
            for metric in metrics_list
        ]
    )


@evaluation_router.post(
    "/traces",
    response_model=schemas.EvaluationAcceptedResponse,
    status_code=status.HTTP_202_ACCEPTED,
)
async def evaluate_trace(
    request: schemas.EvaluationRequest,
) -> schemas.EvaluationAcceptedResponse:
    """
    Evaluate a trace using specified metrics and log feedback scores.

    This endpoint:
    1. Fetches the trace by ID
    2. Extracts data from trace fields based on the field mapping
    3. Runs the configured metrics on the extracted data
    4. Logs feedback scores to the trace

    Request body:
    - trace_id: ID of the trace to evaluate
    - metrics: List of metric configurations with name and init_args
    - field_mapping: Mapping from metric arguments to trace fields
    - project_name: Optional project name (inferred from trace if not provided)

    Returns 202 Accepted - feedback scores are logged asynchronously to the trace.
    """
    service = _get_service()
    return service.evaluate_trace(request)


@healthcheck_router.get("/healthcheck")
async def healthcheck() -> JSONResponse:
    """Health check endpoint."""
    return JSONResponse(content={"status": "ok"}, status_code=200)


def register_exception_handlers(app: "FastAPI") -> None:
    """Register exception handlers on the FastAPI app."""
    from fastapi import FastAPI

    @app.exception_handler(pydantic.ValidationError)
    async def handle_validation_error(
        request: Request, error: pydantic.ValidationError
    ) -> JSONResponse:
        LOGGER.warning("Validation error: %s", error.errors())
        return JSONResponse(
            content={"error": "Invalid input", "details": error.errors()},
            status_code=400,
        )

    @app.exception_handler(exceptions.UnknownMetricError)
    async def handle_unknown_metric_error(
        request: Request, error: exceptions.UnknownMetricError
    ) -> JSONResponse:
        LOGGER.warning("Unknown metric requested: %s", error.metric_name)
        return JSONResponse(
            content={"error": str(error)},
            status_code=400,
        )

    @app.exception_handler(exceptions.MetricInstantiationError)
    async def handle_metric_instantiation_error(
        request: Request, error: exceptions.MetricInstantiationError
    ) -> JSONResponse:
        LOGGER.warning("Metric instantiation failed: %s", error.metric_name)
        return JSONResponse(
            content={"error": str(error)},
            status_code=400,
        )

    @app.exception_handler(exceptions.TraceNotFoundError)
    async def handle_trace_not_found_error(
        request: Request, error: exceptions.TraceNotFoundError
    ) -> JSONResponse:
        LOGGER.warning("Trace not found: %s", error.trace_id)
        return JSONResponse(
            content={"error": str(error)},
            status_code=404,
        )

    @app.exception_handler(exceptions.InvalidFieldMappingError)
    async def handle_invalid_field_mapping_error(
        request: Request, error: exceptions.InvalidFieldMappingError
    ) -> JSONResponse:
        LOGGER.warning("Invalid field mapping: %s", error.field_path)
        return JSONResponse(
            content={"error": str(error)},
            status_code=400,
        )

    @app.exception_handler(exceptions.EvaluationError)
    async def handle_evaluation_error(
        request: Request, error: exceptions.EvaluationError
    ) -> JSONResponse:
        LOGGER.error("Evaluation failed: %s", error.reason)
        return JSONResponse(
            content={"error": str(error)},
            status_code=500,
        )

    @app.exception_handler(Exception)
    async def handle_generic_exception(
        request: Request, error: Exception
    ) -> JSONResponse:
        LOGGER.error("Unexpected error: %s", str(error), exc_info=True)
        return JSONResponse(
            content={"error": f"Internal server error: {str(error)}"},
            status_code=500,
        )
