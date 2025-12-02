"""Eval app module - FastAPI application for running Opik evaluations.

This module is only imported when running the eval-app CLI command.
It requires optional dependencies: fastapi, uvicorn.
"""

import fastapi
from fastapi import middleware


def create_app() -> fastapi.FastAPI:
    """Create and configure the FastAPI application."""
    app = fastapi.FastAPI(
        title="Opik Eval App",
        description="API for running Opik metric evaluations",
        version="1.0.0",
    )

    app.add_middleware(
        middleware.cors.CORSMiddleware,
        allow_origins=["*"],
        allow_credentials=True,
        allow_methods=["*"],
        allow_headers=["*"],
    )

    from .api import routes

    app.include_router(routes.evaluation_router)
    app.include_router(routes.healthcheck_router)
    routes.register_exception_handlers(app)

    return app
