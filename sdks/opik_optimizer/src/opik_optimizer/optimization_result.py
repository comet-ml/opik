"""Module containing the OptimizationResult class."""

from typing import Any, Dict, List, Literal, Optional

import pydantic


class OptimizationResult(pydantic.BaseModel):
    """Result oan optimization run."""

    optimizer: str = "Optimizer"
    
    prompt: List[Dict[Literal["role", "content"], str]]
    score: float
    metric_name: str
    
    details: Dict[str, Any] = pydantic.Field(default_factory=dict)
    history: List[Dict[str, Any]] = []
    llm_calls: Optional[int] = None

    # MIPRO specific
    demonstrations: Optional[List[Dict[str, Any]]] = None
    tool_prompts: Optional[Dict[str, str]] = None
    
    model_config = pydantic.ConfigDict(arbitrary_types_allowed=True)
