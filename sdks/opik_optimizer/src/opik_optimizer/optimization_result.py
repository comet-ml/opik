import pydantic
from typing import Optional, Dict, Any, List


class OptimizationResult(pydantic.BaseModel):
    prompt: str
    score: float
    metric_name: Optional[str] = None
    details: Optional[Dict[str, Any]] = None
