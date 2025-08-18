from typing import Optional
from pydantic import BaseModel


class FewShotExampleStructuredOutputCompliance(pydantic.BaseModel):
    title: str
    output: str
    schema: Optional[str] = None
    score: bool
    reason: str
