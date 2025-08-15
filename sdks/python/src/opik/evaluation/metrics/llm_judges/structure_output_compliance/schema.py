from typing import Optional
from pydantic import BaseModel


class FewShotExampleStructuredOutputCompliance(BaseModel):
    title: str
    output: str
    schema: Optional[str] = None
    score: bool
    reason: str
