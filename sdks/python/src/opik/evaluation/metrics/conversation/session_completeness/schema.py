from typing import List, Optional

import pydantic


class UserGoalsResponse(pydantic.BaseModel):
    user_goals: List[str]

    __hash__ = object.__hash__


class EvaluateUserGoalResponse(pydantic.BaseModel):
    verdict: str
    reason: Optional[str] = pydantic.Field(default=None)

    __hash__ = object.__hash__


class ScoreReasonResponse(pydantic.BaseModel):
    reason: str

    __hash__ = object.__hash__
