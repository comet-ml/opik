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
