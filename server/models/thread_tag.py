from sqlalchemy import Column, String, ForeignKey, UniqueConstraint, Table
from sqlalchemy.orm import declarative_base
from .base import Base

class ThreadTag(Base):
    __tablename__ = "thread_tags"

    thread_id = Column(String, ForeignKey("threads.id", ondelete="CASCADE"), primary_key=True)
    tag_id = Column(String, ForeignKey("tags.id", ondelete="CASCADE"), primary_key=True)

    __table_args__ = (
        UniqueConstraint("thread_id", "tag_id", name="uq_thread_tag"),
    )