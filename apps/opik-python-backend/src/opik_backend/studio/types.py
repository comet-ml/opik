"""Data types and context objects for Optimization Studio."""

import re
from dataclasses import dataclass
from typing import Dict, Any, Optional, List
from uuid import UUID


def _convert_template_syntax(text: str) -> str:
    """Convert double curly braces to single curly braces for template variables.
    
    The frontend uses Mustache-style {{variable}} syntax, but the optimizer
    expects Python-style {variable} syntax.
    
    Args:
        text: String that may contain {{variable}} patterns
        
    Returns:
        String with {{variable}} or {{ name }} converted to {variable} or {name}
    """
    # Convert {{variable}} to {variable}, handling spaces, dots, and hyphens
    return re.sub(r'\{\{\s*([^}]+?)\s*\}\}', r'{\1}', text)


@dataclass
class OptimizationJobContext:
    """Context for an optimization job.
    
    Contains the core identifiers and configuration needed to process
    an optimization job from the Java backend.
    """
    optimization_id: str
    workspace_id: str
    workspace_name: str
    config: Dict[str, Any]
    opik_api_key: Optional[str] = None
    
    @classmethod
    def from_job_message(cls, job_message: Dict[str, Any]) -> "OptimizationJobContext":
        """Create context from job message.
        
        Args:
            job_message: Raw job message from RQ
            
        Returns:
            OptimizationJobContext instance
            
        Raises:
            KeyError: If required fields are missing
        """
        return cls(
            optimization_id=job_message["optimization_id"],
            workspace_id=job_message["workspace_id"],
            workspace_name=job_message["workspace_name"],
            config=job_message["config"],
            opik_api_key=job_message.get("opik_api_key"),
        )


@dataclass
class OptimizationConfig:
    """Parsed optimization configuration.
    
    Extracts and structures the nested configuration from the job message
    for easier access.
    """
    # Dataset
    dataset_name: str
    
    # Prompt
    prompt_messages: List[Dict[str, str]]
    
    # Model
    model: str
    model_params: Dict[str, Any]
    
    # Metric
    metric_type: str
    metric_params: Dict[str, Any]
    
    # Optimizer
    optimizer_type: str
    optimizer_params: Dict[str, Any]
    
    @classmethod
    def from_dict(cls, config: Dict[str, Any]) -> "OptimizationConfig":
        """Parse config dict into typed object.
        
        Args:
            config: Configuration dictionary from job message
            
        Returns:
            OptimizationConfig instance
            
        Raises:
            KeyError: If required fields are missing
            ValueError: If metrics list is empty
        """
        # Extract metric config (use first metric for now)
        metric_config_list = config["evaluation"]["metrics"]
        if not metric_config_list:
            raise ValueError("At least one metric must be defined")
        
        metric_config = metric_config_list[0]
        
        # Convert prompt messages template syntax from {{var}} (FE-style) to {var} (optimizer-style)
        prompt_messages = []
        for msg in config["prompt"]["messages"]:
            converted_msg = {
                "role": msg["role"],
                "content": _convert_template_syntax(msg["content"]) if isinstance(msg["content"], str) else msg["content"]
            }
            prompt_messages.append(converted_msg)
        
        return cls(
            dataset_name=config["dataset_name"],
            prompt_messages=prompt_messages,
            model=config["llm_model"]["model"],
            model_params=config["llm_model"].get("parameters", {}),
            metric_type=metric_config["type"],
            metric_params=metric_config.get("parameters", {}),
            optimizer_type=config["optimizer"]["type"],
            optimizer_params=config["optimizer"].get("parameters", {}),
        )


@dataclass
class OptimizationResult:
    """Result of an optimization run."""
    optimization_id: str
    final_score: float
    initial_score: Optional[float]
    metric_name: str
    timestamp: str
    
    def to_dict(self) -> Dict[str, Any]:
        """Convert result to dictionary for API response.
        
        Returns:
            Dictionary representation of the result
        """
        return {
            "status": "success",
            "optimization_id": self.optimization_id,
            "final_score": self.final_score,
            "initial_score": self.initial_score,
            "metric_name": self.metric_name,
            "timestamp": self.timestamp,
        }

