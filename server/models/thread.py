from sqlalchemy import Column, String, DateTime
from sqlalchemy.orm import relationship
from .base import Base
from .thread_tag import ThreadTag

class Thread(Base):
    __tablename__ = "threads"

    id = Column(String, primary_key=True)
    title = Column(String, nullable=False)
    created_at = Column(DateTime, nullable=False)

    tags = relationship(
        "Tag",
        secondary=ThreadTag.__table__,
        back_populates="threads",
        lazy="selectin",
    )