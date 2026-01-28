import pydantic


class EvaluateUserFrustrationResponse(pydantic.BaseModel):
    verdict: str
    reason: str | None = pydantic.Field(default=None)

    __hash__ = object.__hash__


class ScoreReasonResponse(pydantic.BaseModel):
    reason: str

    __hash__ = object.__hash__
