from typing import List, Optional

import pydantic


class UserGoalsResponse(pydantic.BaseModel):
    user_goals: List[str]

    __hash__ = object.__hash__


class EvaluateUserGoalResponse(pydantic.BaseModel):
    verdict: str
    # Listed without a default so OpenAI's strict-mode structured outputs keeps
    # the property in ``required`` (defaults disable strict mode and let the
    # model emit duplicate / drifted JSON). The field stays nullable so the
    # model can still set ``reason: null`` when no rationale is needed.
    reason: Optional[str]

    __hash__ = object.__hash__


class ScoreReasonResponse(pydantic.BaseModel):
    reason: str

    __hash__ = object.__hash__
