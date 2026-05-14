from pydantic import BaseModel


class ProjectCreate(BaseModel):
    name: str
    workspace: str | None = None


class ProjectResponse(BaseModel):
    id: str
    name: str
