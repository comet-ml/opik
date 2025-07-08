from typing import Optional

import pydantic


class EvaluateUserFrustrationResponse(pydantic.BaseModel):
    verdict: str
    reason: Optional[str] = pydantic.Field(default=None)

    __hash__ = object.__hash__


class ScoreReasonResponse(pydantic.BaseModel):
    reason: str

    __hash__ = object.__hash__
