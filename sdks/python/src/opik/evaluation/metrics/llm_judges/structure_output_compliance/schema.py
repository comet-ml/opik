from typing import Optional
import pydantic


class FewShotExampleStructuredOutputCompliance(pydantic.BaseModel):
    title: str
    output: str
    schema: Optional[str] = None
    score: bool
    reason: str
