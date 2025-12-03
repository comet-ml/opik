"""API routes for the eval app."""

import logging
from typing import Optional

import fastapi
import pydantic
from fastapi import responses

from .. import exceptions
from .. import schemas
from ..services import eval_service as eval_service_module

LOGGER = logging.getLogger(__name__)

evaluation_router = fastapi.APIRouter(
    prefix="/api/v1/evaluation", tags=["evaluation"]
)
healthcheck_router = fastapi.APIRouter(tags=["healthcheck"])

_service_instance: Optional[eval_service_module.EvalService] = None


def _get_service() -> eval_service_module.EvalService:
    """Get or create the eval service instance."""
    global _service_instance
    if _service_instance is None:
        _service_instance = eval_service_module.create_service()
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
                init_params=[
                    schemas.MetricParamDescriptor(
                        name=p.name,
                        required=p.required,
                        type=p.type,
                        default=p.default,
                        description=p.description,
                    )
                    for p in m.init_params
                ],
                score_params=[
                    schemas.MetricParamDescriptor(
                        name=p.name,
                        required=p.required,
                        type=p.type,
                        default=p.default,
                        description=p.description,
                    )
                    for p in m.score_params
                ],
            )
            for m in metrics_list
        ]
    )


@evaluation_router.post(
    "/traces/{trace_id}",
    response_model=schemas.EvaluationAcceptedResponse,
    status_code=fastapi.status.HTTP_202_ACCEPTED,
)
def evaluate_trace(
    trace_id: str,
    request: schemas.EvaluationRequest,
) -> schemas.EvaluationAcceptedResponse:
    """Evaluate a trace and log feedback scores."""
    service = _get_service()
    return service.evaluate_trace(trace_id, request)


@healthcheck_router.get("/healthcheck")
def healthcheck() -> responses.JSONResponse:
    """Health check endpoint."""
    return responses.JSONResponse(content={"status": "ok"})


def register_exception_handlers(app: fastapi.FastAPI) -> None:
    """Register exception handlers for the app."""

    @app.exception_handler(exceptions.UnknownMetricError)
    def unknown_metric_handler(
        request: fastapi.Request, exc: exceptions.UnknownMetricError
    ) -> responses.JSONResponse:
        return responses.JSONResponse(
            status_code=fastapi.status.HTTP_404_NOT_FOUND,
            content={"detail": str(exc), "metric_name": exc.metric_name},
        )

    @app.exception_handler(exceptions.TraceNotFoundError)
    def trace_not_found_handler(
        request: fastapi.Request, exc: exceptions.TraceNotFoundError
    ) -> responses.JSONResponse:
        return responses.JSONResponse(
            status_code=fastapi.status.HTTP_404_NOT_FOUND,
            content={"detail": str(exc), "trace_id": exc.trace_id},
        )

    @app.exception_handler(exceptions.MetricInstantiationError)
    def metric_instantiation_handler(
        request: fastapi.Request, exc: exceptions.MetricInstantiationError
    ) -> responses.JSONResponse:
        return responses.JSONResponse(
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
    ) -> responses.JSONResponse:
        return responses.JSONResponse(
            status_code=fastapi.status.HTTP_400_BAD_REQUEST,
            content={"detail": str(exc), "field": exc.field, "error": exc.error},
        )

    @app.exception_handler(exceptions.EvaluationError)
    def evaluation_error_handler(
        request: fastapi.Request, exc: exceptions.EvaluationError
    ) -> responses.JSONResponse:
        return responses.JSONResponse(
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
    ) -> responses.JSONResponse:
        return responses.JSONResponse(
            status_code=fastapi.status.HTTP_422_UNPROCESSABLE_ENTITY,
            content={"detail": exc.errors()},
        )

