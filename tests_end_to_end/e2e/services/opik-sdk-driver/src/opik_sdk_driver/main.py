from fastapi import FastAPI

from .routes import health, projects

app = FastAPI(title="opik-sdk-driver", version="0.0.1")
app.include_router(health.router)
app.include_router(projects.router)
