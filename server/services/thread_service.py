from typing import List, Dict, Set
from sqlalchemy.ext.asyncio import AsyncSession
from sqlalchemy import select, delete
from opik.server.models.thread import Thread
from opik.server.models.tag import Tag
from opik.server.models.thread_tag import ThreadTag
from opik.server.utils.auth import ensure_user_can_modify_threads
from opik.server.utils.ids import new_id
from opik.server.utils.audit import write_audit_log

def _normalize_tag(name: str) -> str:
    return name.strip()

async def _get_threads(session: AsyncSession, ids: List[str]) -> Dict[str, Thread]:
    q = await session.execute(select(Thread).where(Thread.id.in_(ids)))
    threads = q.scalars().all()
    return {t.id: t for t in threads}

async def _get_or_create_tags(session: AsyncSession, names: Set[str]) -> Dict[str, Tag]:
    if not names:
        return {}
    # Fetch existing
    q = await session.execute(select(Tag).where(Tag.name.in_(list(names))))
    existing = {t.name: t for t in q.scalars().all()}
    to_create = [n for n in names if n not in existing]
    for name in to_create:
        tag = Tag(id=new_id("tag"), name=name)
        session.add(tag)
        existing[name] = tag
    return existing

async def apply_bulk_tags(
    session: AsyncSession,
    user_id: str,
    thread_ids: List[str],
    add: List[str],
    remove: List[str],
) -> Dict:
    add_set = { _normalize_tag(a) for a in add if a.strip() }
    remove_set = { _normalize_tag(r) for r in remove if r.strip() }

    if not add_set and not remove_set:
        return {"updated": [], "failed": [{"id": "", "error": "No tags to add or remove"}]}

    # Authorization
    await ensure_user_can_modify_threads(session, user_id, thread_ids)

    # Fetch threads
    tmap = await _get_threads(session, thread_ids)
    # Prepare tags
    tag_map = await _get_or_create_tags(session, add_set | remove_set)

    updated, failed = [], []

    # Bulk remove in one query (for performance)
    if remove_set:
        remove_tag_ids = [tag_map[name].id for name in remove_set if name in tag_map]
        await session.execute(
            delete(ThreadTag)
            .where(ThreadTag.thread_id.in_(thread_ids))
            .where(ThreadTag.tag_id.in_(remove_tag_ids))
        )

    # Bulk add via upsert-like behavior
    for tid in thread_ids:
        thread = tmap.get(tid)
        if not thread:
            failed.append({"id": tid, "error": "Thread not found or unauthorized"})
            continue
        try:
            # Add tags: create association rows (unique constraint prevents duplicates)
            for name in add_set:
                tag = tag_map.get(name)
                if not tag:
                    continue
                session.add(ThreadTag(thread_id=tid, tag_id=tag.id))
            updated.append(tid)
            await write_audit_log(
                session=session,
                user_id=user_id,
                entity_type="thread",
                entity_id=tid,
                action="bulk_tag",
                metadata={"add": list(add_set), "remove": list(remove_set)},
            )
        except Exception as e:
            failed.append({"id": tid, "error": str(e)})

    await session.commit()
    return {"updated": updated, "failed": failed}