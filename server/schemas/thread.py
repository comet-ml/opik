from pydantic import BaseModel, Field, conlist
from typing import List

class BatchTagRequest(BaseModel):
    thread_ids: conlist(str, min_items=1) = Field(..., description="IDs of threads to tag")
    add: List[str] = Field(default_factory=list, description="Tag names to add")
    remove: List[str] = Field(default_factory=list, description="Tag names to remove")

class BatchTagResultItem(BaseModel):
    id: str
    error: str

class BatchTagResponse(BaseModel):
    updated: List[str]
    failed: List[BatchTagResultItem]