import pydantic


class FewShotExampleStructuredOutputCompliance(pydantic.BaseModel):
    title: str
    output: str
    output_schema: str | None = None
    score: bool
    reason: str


class StructuredOutputComplianceResponseFormat(pydantic.BaseModel):
    score: bool
    reason: list[str]
