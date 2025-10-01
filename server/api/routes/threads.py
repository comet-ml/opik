from fastapi import APIRouter, Depends, HTTPException
from sqlalchemy.ext.asyncio import AsyncSession
from opik.server.db.session import get_session
from opik.server.schemas.thread import BatchTagRequest, BatchTagResponse, BatchTagResultItem
from opik.server.services.thread_service import apply_bulk_tags
from opik.server.utils.auth import get_current_user

router = APIRouter(prefix="/api/threads", tags=["threads"])

@router.post("/batch/tags", response_model=BatchTagResponse)
async def batch_tag_threads(
    payload: BatchTagRequest,
    session: AsyncSession = Depends(get_session),
    user = Depends(get_current_user),
):
    if not payload.add and not payload.remove:
        raise HTTPException(status_code=400, detail="No tags to add or remove")
    result = await apply_bulk_tags(
        session=session,
        user_id=user.id,
        thread_ids=payload.thread_ids,
        add=payload.add,
        remove=payload.remove,
    )
    # Pydantic model coercion
    return BatchTagResponse(
        updated=result["updated"],
        failed=[BatchTagResultItem(**f) for f in result["failed"]],
    )