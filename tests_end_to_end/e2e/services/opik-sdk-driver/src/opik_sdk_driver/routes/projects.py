import atexit

from fastapi import APIRouter, Header, HTTPException
from opik.rest_api.core.api_error import ApiError

from ..opik_factory import make_opik_client
from ..schemas import ProjectCreate, ProjectResponse

router = APIRouter(prefix="/projects", tags=["projects"])


@router.post("", response_model=ProjectResponse, status_code=201)
def create_project(
    body: ProjectCreate,
    x_opik_api_key: str | None = Header(default=None),
) -> ProjectResponse:
    # _rest_client because opik.Opik() doesn't yet expose a public
    # create_project(); switch when the SDK adds one. ApiError raised by
    # the SDK is translated to HTTP by the app-wide exception handler.
    client = make_opik_client(workspace=body.workspace, api_key=x_opik_api_key)
    try:
        try:
            client._rest_client.projects.create_project(name=body.name)
        except ApiError as err:
            # 409 means the name is already taken, which for this route is a
            # success: the caller wants the project to exist and be returned.
            # It is also what a retried create looks like after the create
            # committed but the read below was throttled, so swallowing it here
            # is what makes the whole route safe to retry.
            if err.status_code != 409:
                raise
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
