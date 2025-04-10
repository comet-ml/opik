import pydantic
from typing import Optional, Dict, Any, List


class OptimizationResult(pydantic.BaseModel):
    prompt: str
    score: float
    metric_name: Optional[str] = None
    details: Optional[Dict[str, Any]] = None

    def __str__(self):
        return f"""
Optimization Results:
    Best prompt: {self.prompt}
    Best score: {self.score:.4f}
"""
