"""API routes for the eval app."""

import logging
from typing import Optional

import fastapi
import fastapi.responses
import pydantic

from .. import eval_service
from .. import exceptions
from .. import schemas

LOGGER = logging.getLogger(__name__)

evaluation_router = fastapi.APIRouter(prefix="/api/v1/evaluation", tags=["evaluation"])
healthcheck_router = fastapi.APIRouter(tags=["healthcheck"])

_service_instance: Optional[eval_service.EvalService] = None


def _get_service() -> eval_service.EvalService:
    """Get or create the eval service instance."""
    global _service_instance
    if _service_instance is None:
        _service_instance = eval_service.create_service()
    return _service_instance


@evaluation_router.get("/metrics", response_model=schemas.MetricsListResponse)
def list_metrics() -> schemas.MetricsListResponse:
    """List all available metrics with their parameters."""
    service = _get_service()
    metrics_list = service.list_metrics()
    return schemas.MetricsListResponse(
        metrics=[
            schemas.MetricDescriptorResponse(
                name=m.name,
                description=m.description,
                score_description=m.score_description,
                init_params=[
                    schemas.MetricParamDescriptor(
                        name=p.name,
                        required=p.required,
                        type=p.type,
                        default=p.default,
                    )
                    for p in m.init_params
                ],
                score_params=[
                    schemas.MetricParamDescriptor(
                        name=p.name,
                        required=p.required,
                        type=p.type,
                        default=p.default,
                    )
                    for p in m.score_params
                ],
            )
            for m in metrics_list
        ]
    )


@evaluation_router.post(
    "/trace",
    response_model=schemas.EvaluationAcceptedResponse,
    status_code=fastapi.status.HTTP_202_ACCEPTED,
)
def evaluate_trace(
    request: schemas.EvaluationRequest,
    background_tasks: fastapi.BackgroundTasks,
) -> schemas.EvaluationAcceptedResponse:
    """Evaluate a trace and log feedback scores.

    Returns immediately with 202 Accepted. Evaluation runs in the background.
    """
    service = _get_service()

    # Schedule evaluation in background so the endpoint returns immediately
    # This prevents blocking workers and allows healthcheck to respond
    background_tasks.add_task(
        service.evaluate_trace,
        request.trace_id,
        request,
    )

    return schemas.EvaluationAcceptedResponse(
        trace_id=request.trace_id,
        metrics_count=len(request.metrics),
    )


@healthcheck_router.get("/healthcheck")
def healthcheck() -> fastapi.responses.JSONResponse:
    """Health check endpoint."""
    return fastapi.responses.JSONResponse(content={"status": "ok"})


def register_exception_handlers(app: fastapi.FastAPI) -> None:
    """Register exception handlers for the app."""

    @app.exception_handler(exceptions.UnknownMetricError)
    def unknown_metric_handler(
        request: fastapi.Request, exc: exceptions.UnknownMetricError
    ) -> fastapi.responses.JSONResponse:
        return fastapi.responses.JSONResponse(
            status_code=fastapi.status.HTTP_404_NOT_FOUND,
            content={"detail": str(exc), "metric_name": exc.metric_name},
        )

    @app.exception_handler(exceptions.TraceNotFoundError)
    def trace_not_found_handler(
        request: fastapi.Request, exc: exceptions.TraceNotFoundError
    ) -> fastapi.responses.JSONResponse:
        return fastapi.responses.JSONResponse(
            status_code=fastapi.status.HTTP_404_NOT_FOUND,
            content={"detail": str(exc), "trace_id": exc.trace_id},
        )

    @app.exception_handler(exceptions.MetricInstantiationError)
    def metric_instantiation_handler(
        request: fastapi.Request, exc: exceptions.MetricInstantiationError
    ) -> fastapi.responses.JSONResponse:
        return fastapi.responses.JSONResponse(
            status_code=fastapi.status.HTTP_400_BAD_REQUEST,
            content={
                "detail": str(exc),
                "metric_name": exc.metric_name,
                "error": exc.error,
            },
        )

    @app.exception_handler(exceptions.InvalidFieldMappingError)
    def invalid_field_mapping_handler(
        request: fastapi.Request, exc: exceptions.InvalidFieldMappingError
    ) -> fastapi.responses.JSONResponse:
        return fastapi.responses.JSONResponse(
            status_code=fastapi.status.HTTP_400_BAD_REQUEST,
            content={"detail": str(exc), "field": exc.field, "error": exc.error},
        )

    @app.exception_handler(exceptions.EvaluationError)
    def evaluation_error_handler(
        request: fastapi.Request, exc: exceptions.EvaluationError
    ) -> fastapi.responses.JSONResponse:
        return fastapi.responses.JSONResponse(
            status_code=fastapi.status.HTTP_500_INTERNAL_SERVER_ERROR,
            content={
                "detail": str(exc),
                "metric_name": exc.metric_name,
                "error": exc.error,
            },
        )

    @app.exception_handler(pydantic.ValidationError)
    def validation_error_handler(
        request: fastapi.Request, exc: pydantic.ValidationError
    ) -> fastapi.responses.JSONResponse:
        return fastapi.responses.JSONResponse(
            status_code=fastapi.status.HTTP_422_UNPROCESSABLE_ENTITY,
            content={"detail": exc.errors()},
        )
