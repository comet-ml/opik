from typing import Optional, List
import pydantic


class FewShotExampleStructuredOutputCompliance(pydantic.BaseModel):
    title: str
    output: str
    schema: Optional[str] = None
    score: bool
    reason: str


class StructuredOutputComplianceResponseFormat(pydantic.BaseModel):
    score: bool
    reason: List[str]
