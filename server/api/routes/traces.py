from fastapi import APIRouter, Depends
from sqlalchemy.ext.asyncio import AsyncSession
from sqlalchemy import select, exists
from opik.server.db.session import get_session
from opik.server.models.trace import Trace
from opik.server.models.comment import Comment
from opik.server.utils.auth import get_current_user

router = APIRouter(prefix="/api/traces", tags=["traces"])

@router.get("/")
async def list_traces(
    has_comment: bool | None = None,
    session: AsyncSession = Depends(get_session),
    user=Depends(get_current_user),
):
    q = select(Trace)
    if has_comment is True:
        q = q.where(exists().where(Comment.trace_id == Trace.id))
    elif has_comment is False:
        q = q.where(~exists().where(Comment.trace_id == Trace.id))

    result = await session.execute(q)
    traces = result.scalars().all()
    return [t.as_dict() for t in traces]