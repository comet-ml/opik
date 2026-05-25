from typing import Any

from pydantic import BaseModel, ConfigDict


class ProjectCreate(BaseModel):
    # Reject unknown fields so a typo like `wokspace` fails 422 instead of
    # silently defaulting to the bridge's env workspace.
    model_config = ConfigDict(extra="forbid")

    name: str
    workspace: str | None = None


class ProjectResponse(BaseModel):
    id: str
    name: str


class TraceCreate(BaseModel):
    model_config = ConfigDict(extra="forbid")

    project_name: str
    name: str
    input: str
    output: str
    workspace: str | None = None


class TraceResponse(BaseModel):
    id: str
    name: str
    project_id: str


class DatasetCreate(BaseModel):
    model_config = ConfigDict(extra="forbid")

    name: str
    project_name: str
    description: str | None = None
    items: list[dict[str, Any]] | None = None
    workspace: str | None = None


class DatasetResponse(BaseModel):
    id: str
    name: str
