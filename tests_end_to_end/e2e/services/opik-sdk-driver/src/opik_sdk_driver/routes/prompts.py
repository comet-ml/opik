import atexit

from fastapi import APIRouter, Header

from ..opik_factory import make_opik_client
from ..schemas import ChatPromptCreate, PromptResponse, TextPromptCreate

router = APIRouter(prefix="/prompts", tags=["prompts"])


@router.post("/text", response_model=PromptResponse, status_code=201)
def create_text_prompt(
    body: TextPromptCreate,
    x_opik_api_key: str | None = Header(default=None),
) -> PromptResponse:
    client = make_opik_client(workspace=body.workspace, api_key=x_opik_api_key)
    try:
        prompt = client.create_prompt(
            name=body.name,
            prompt=body.prompt,
            description=body.description,
            project_name=body.project_name,
        )
    finally:
        atexit.unregister(client.end)
        client.end(flush=True)
    return PromptResponse(id=str(prompt.id), name=prompt.name)


@router.post("/chat", response_model=PromptResponse, status_code=201)
def create_chat_prompt(
    body: ChatPromptCreate,
    x_opik_api_key: str | None = Header(default=None),
) -> PromptResponse:
    client = make_opik_client(workspace=body.workspace, api_key=x_opik_api_key)
    try:
        prompt = client.create_chat_prompt(
            name=body.name,
            messages=body.messages,
            description=body.description,
            project_name=body.project_name,
        )
    finally:
        atexit.unregister(client.end)
        client.end(flush=True)
    return PromptResponse(id=str(prompt.id), name=prompt.name)
