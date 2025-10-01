import pytest
from httpx import AsyncClient
from sqlalchemy.ext.asyncio import AsyncSession
from opik.server.models.thread import Thread
from opik.server.models.tag import Tag
from opik.server.models.thread_tag import ThreadTag
from opik.server.utils.ids import new_id

@pytest.mark.asyncio
async def test_batch_add_and_remove_tags(app, session: AsyncSession):
    # Setup: create threads
    t1 = Thread(id=new_id("thread"), title="A", created_at=None)
    t2 = Thread(id=new_id("thread"), title="B", created_at=None)
    session.add_all([t1, t2])
    await session.commit()

    async with AsyncClient(app=app, base_url="http://test") as client:
        # Add tags
        res = await client.post("/api/threads/batch/tags", json={
            "thread_ids": [t1.id, t2.id],
            "add": ["Needs review", "Bug"],
            "remove": []
        })
        assert res.status_code == 200
        data = res.json()
        assert set(data["updated"]) == {t1.id, t2.id}
        assert data["failed"] == []

        # Remove one tag
        res2 = await client.post("/api/threads/batch/tags", json={
            "thread_ids": [t1.id, t2.id],
            "add": [],
            "remove": ["Needs review"]
        })
        assert res2.status_code == 200
        data2 = res2.json()
        assert set(data2["updated"]) == {t1.id, t2.id}

@pytest.mark.asyncio
async def test_batch_partial_failures(app, session: AsyncSession):
    # One valid thread, one invalid ID
    t1 = Thread(id=new_id("thread"), title="Valid", created_at=None)
    session.add(t1)
    await session.commit()

    async with AsyncClient(app=app, base_url="http://test") as client:
        res = await client.post("/api/threads/batch/tags", json={
            "thread_ids": [t1.id, "nonexistent"],
            "add": ["X"],
            "remove": []
        })
        assert res.status_code == 200
        body = res.json()
        assert t1.id in body["updated"]
        assert any(f["id"] == "nonexistent" for f in body["failed"])