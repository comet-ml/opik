from sqlalchemy import Column, String, DateTime
from sqlalchemy.orm import relationship
from .base import Base
from .thread_tag import ThreadTag

class Tag(Base):
    __tablename__ = "tags"

    id = Column(String, primary_key=True)
    name = Column(String, nullable=False, unique=True)
    created_at = Column(DateTime, nullable=False)

    threads = relationship(
        "Thread",
        secondary=ThreadTag.__table__,
        back_populates="tags",
        lazy="selectin",
    )