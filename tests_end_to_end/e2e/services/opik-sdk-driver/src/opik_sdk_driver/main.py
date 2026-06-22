from fastapi import FastAPI, Request
from fastapi.responses import JSONResponse
from opik.rest_api.core.api_error import ApiError

from .routes import (
    datasets,
    experiments,
    feedback_definitions,
    health,
    projects,
    prompts, test_suites,
    traces,
)

app = FastAPI(title="opik-sdk-driver", version="0.0.1")


@app.exception_handler(ApiError)
async def _api_error_handler(_request: Request, exc: ApiError) -> JSONResponse:
    # Centralize SDK -> HTTP translation so every route gets backend
    # status code + body propagated identically (409 "already exists",
    # 401 "not allowed", etc.) without per-route try/except boilerplate.
    return JSONResponse(status_code=exc.status_code, content=exc.body)


app.include_router(health.router)
app.include_router(projects.router)
app.include_router(traces.router)
app.include_router(feedback_definitions.router)
app.include_router(datasets.router)
app.include_router(experiments.router)
app.include_router(prompts.router)
app.include_router(test_suites.router)
