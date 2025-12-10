"""Eval app entrypoint - FastAPI application for running Opik evaluations.

This module is only imported when running the eval-app CLI command.
It requires optional dependencies: fastapi, uvicorn.
"""

import logging
import os

import fastapi
import starlette.middleware.cors
from .api import routes


def _configure_logging() -> None:
    """Configure logging with worker ID prefix for this process."""
    worker_id = os.getpid()
    formatter = logging.Formatter(f"[worker-{worker_id}] %(levelname)s - %(message)s")

    handler = logging.StreamHandler()
    handler.setFormatter(formatter)

    eval_app_logger = logging.getLogger("opik.eval_app")
    eval_app_logger.handlers.clear()
    eval_app_logger.addHandler(handler)
    eval_app_logger.setLevel(logging.INFO)
    eval_app_logger.propagate = False


def create_app() -> fastapi.FastAPI:
    """Create and configure the FastAPI application."""
    _configure_logging()

    app = fastapi.FastAPI(
        title="Opik Eval App",
        description="API for running Opik metric evaluations on playground traces",
        version="0.0.1",
    )

    app.add_middleware(
        starlette.middleware.cors.CORSMiddleware,
        allow_origins=["*"],
        allow_credentials=True,
        allow_methods=["*"],
        allow_headers=["*"],
    )

    app.include_router(routes.evaluation_router)
    app.include_router(routes.healthcheck_router)
    routes.register_exception_handlers(app)

    return app
