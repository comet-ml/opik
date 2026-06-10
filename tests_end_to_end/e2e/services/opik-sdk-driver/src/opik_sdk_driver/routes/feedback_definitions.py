import atexit

from fastapi import APIRouter, Header, HTTPException
from opik.rest_api.types import (
    FeedbackCreate_Numerical,
    NumericalFeedbackDetailCreate,
)

from ..opik_factory import make_opik_client
from ..schemas import FeedbackDefinitionCreate, FeedbackDefinitionResponse

router = APIRouter(prefix="/feedback-definitions", tags=["feedback-definitions"])


@router.post("", response_model=FeedbackDefinitionResponse, status_code=201)
def create_feedback_definition(
    body: FeedbackDefinitionCreate,
    x_opik_api_key: str | None = Header(default=None),
) -> FeedbackDefinitionResponse:
    # _rest_client because opik.Opik() doesn't expose a public
    # create_feedback_definition(); a numerical definition is the precondition
    # for the UI's manual named-score editor. Workspace-scoped, so the caller
    # is responsible for deletion (no project cascade). ApiError is translated
    # to HTTP by the app-wide exception handler.
    client = make_opik_client(workspace=body.workspace, api_key=x_opik_api_key)
    try:
        client._rest_client.feedback_definitions.create_feedback_definition(
            request=FeedbackCreate_Numerical(
                name=body.name,
                details=NumericalFeedbackDetailCreate(min=body.min, max=body.max),
            )
        )
        page = client._rest_client.feedback_definitions.find_feedback_definitions(
            name=body.name, page=1, size=1
        )
    finally:
        client.end(flush=False)
        atexit.unregister(client.end)
    if not page.content:
        raise HTTPException(
            status_code=500,
            detail=f"Feedback definition {body.name} not visible after create",
        )
    return FeedbackDefinitionResponse(
        id=str(page.content[0].id), name=page.content[0].name
    )


@router.delete("/{definition_id}")
def delete_feedback_definition(
    definition_id: str,
    x_opik_api_key: str | None = Header(default=None),
    workspace: str | None = Header(default=None, alias="X-Opik-Workspace"),
) -> dict[str, bool]:
    # Returns a JSON body (not 204) so the TS bridge client, which parses every
    # successful response as JSON, doesn't choke on an empty body.
    client = make_opik_client(workspace=workspace, api_key=x_opik_api_key)
    try:
        client._rest_client.feedback_definitions.delete_feedback_definition_by_id(
            definition_id
        )
    finally:
        client.end(flush=False)
        atexit.unregister(client.end)
    return {"deleted": True}
