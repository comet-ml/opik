import atexit

import opik
from fastapi import APIRouter, HTTPException
from opik.rest_api.core.api_error import ApiError

from ..schemas import ProjectCreate, ProjectResponse

router = APIRouter(prefix="/projects", tags=["projects"])


@router.post("", response_model=ProjectResponse, status_code=201)
def create_project(body: ProjectCreate) -> ProjectResponse:
    # _rest_client because opik.Opik() doesn't yet expose a public
    # create_project(); switch when the SDK adds one.
    client = opik.Opik(workspace=body.workspace) if body.workspace else opik.Opik()
    # Opik.__init__ spawns a streamer thread and registers an atexit handler.
    # We never enqueue messages here, so close the streamer and drop the
    # handler immediately rather than leaking one of each per request.
    try:
        try:
            client._rest_client.projects.create_project(name=body.name)
            page = client._rest_client.projects.find_projects(name=body.name, page=1, size=1)
        except ApiError as e:
            # Preserve the backend's status code and detail body so callers
            # see e.g. 409 "Project already exists" instead of an opaque 500.
            raise HTTPException(status_code=e.status_code, detail=e.body) from e
    finally:
        client.end(flush=False)
        atexit.unregister(client.end)
    if not page.content:
        raise HTTPException(
            status_code=500,
            detail=f"Project {body.name} not visible after create",
        )
    return ProjectResponse(id=str(page.content[0].id), name=page.content[0].name)
