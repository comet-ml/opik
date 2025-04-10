import pydantic
from typing import Optional, Dict, Any, List


class OptimizationResult(pydantic.BaseModel):
    prompt: str
    score: float
    metric_name: Optional[str] = None
    details: Optional[Dict[str, Any]] = None

    @property
    def initial_prompt(self) -> str:
        return self.details.get("initial_prompt", self.prompt) if self.details else self.prompt

    @property
    def initial_score(self) -> float:
        return self.details.get("initial_score", self.score) if self.details else self.score

    @property
    def final_prompt(self) -> str:
        return self.prompt

    @property
    def final_score(self) -> float:
        return self.score

    @property
    def total_rounds(self) -> int:
        return self.details.get("total_rounds", 1) if self.details else 1

    @property
    def stopped_early(self) -> bool:
        return self.details.get("stopped_early", False) if self.details else False

    @property
    def rounds(self) -> List[Dict[str, Any]]:
        return self.details.get("rounds", []) if self.details else []
