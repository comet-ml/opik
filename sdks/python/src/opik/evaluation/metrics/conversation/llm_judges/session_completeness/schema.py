import pydantic


class UserGoalsResponse(pydantic.BaseModel):
    user_goals: list[str]

    __hash__ = object.__hash__


class EvaluateUserGoalResponse(pydantic.BaseModel):
    verdict: str
    reason: str | None = pydantic.Field(default=None)

    __hash__ = object.__hash__


class ScoreReasonResponse(pydantic.BaseModel):
    reason: str

    __hash__ = object.__hash__
