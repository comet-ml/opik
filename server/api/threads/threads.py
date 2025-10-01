from fastapi import APIRouter, Depends
from sqlalchemy.ext.asyncio import AsyncSession
from sqlalchemy import select, exists
from opik.server.db.session import get_session
from opik.server.models.thread import Thread
from opik.server.models.comment import Comment  # assuming comments table exists
from opik.server.utils.auth import get_current_user

router = APIRouter(prefix="/api/threads", tags=["threads"])

@router.get("/")
async def list_threads(
    has_comment: bool | None = None,
    session: AsyncSession = Depends(get_session),
    user=Depends(get_current_user),
):
    q = select(Thread)
    if has_comment is True:
        q = q.where(exists().where(Comment.thread_id == Thread.id))
    elif has_comment is False:
        q = q.where(~exists().where(Comment.thread_id == Thread.id))

    result = await session.execute(q)
    threads = result.scalars().all()
    return [t.as_dict() for t in threads]