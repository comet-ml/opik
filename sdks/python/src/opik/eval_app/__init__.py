"""Eval app module - FastAPI application for running Opik evaluations.

This module is only imported when running the eval-app CLI command.
It requires optional dependencies: fastapi, uvicorn.
"""


def create_app():
    """Create and configure the FastAPI application."""
    import fastapi
    from starlette.middleware.cors import CORSMiddleware

    app = fastapi.FastAPI(
        title="Opik Eval App",
        description="API for running Opik metric evaluations on playground traces",
        version="1.0.0",
    )

    app.add_middleware(
        CORSMiddleware,
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

