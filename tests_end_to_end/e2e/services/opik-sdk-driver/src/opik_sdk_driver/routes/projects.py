import atexit

import opik
from fastapi import APIRouter, HTTPException

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
        client._rest_client.projects.create_project(name=body.name)
        page = client._rest_client.projects.find_projects(name=body.name, page=1, size=1)
    finally:
        client.end(flush=False)
        atexit.unregister(client.end)
    if not page.content:
        raise HTTPException(
            status_code=500,
            detail=f"Project {body.name} not visible after create",
        )
    return ProjectResponse(id=str(page.content[0].id), name=page.content[0].name)
