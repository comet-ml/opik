import pytest
from httpx import AsyncClient
from opik.server.models.thread import Thread
from opik.server.models.trace import Trace
from opik.server.models.comment import Comment
from opik.server.utils.ids import new_id

@pytest.mark.asyncio
async def test_filter_threads_with_comments(app, session):
    t1 = Thread(id=new_id("thread"), title="With comment")
    t2 = Thread(id=new_id("thread"), title="No comment")
    session.add_all([t1, t2])
    await session.commit()

    c = Comment(id=new_id("comment"), thread_id=t1.id, text="hello")
    session.add(c)
    await session.commit()

    async with AsyncClient(app=app, base_url="http://test") as client:
        res = await client.get("/api/threads", params={"has_comment": True})
        ids = [t["id"] for t in res.json()]
        assert t1.id in ids and t2.id not in ids

        res2 = await client.get("/api/threads", params={"has_comment": False})
        ids2 = [t["id"] for t in res2.json()]
        assert t2.id in ids2 and t1.id not in ids2