from typing import List, Optional

import pydantic


class UserGoalsResponse(pydantic.BaseModel):
    user_goals: List[str]


class EvaluateUserGoalResponse(pydantic.BaseModel):
    verdict: str
    reason: Optional[str] = pydantic.Field(default=None)


class ScoreReasonResponse(pydantic.BaseModel):
    reason: str
