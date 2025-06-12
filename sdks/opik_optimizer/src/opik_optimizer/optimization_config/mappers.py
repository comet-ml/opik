from typing import Dict, Callable, Optional, Any, Union

EVALUATED_LLM_TASK_OUTPUT = "llm_output"

class Mapper:
    """Base class for mapping functions that transform data between different formats."""
    
    def __init__(self, name: Optional[str] = None, transform: Optional[Callable[[Any], Any]] = None):
        if name is not None and transform is not None:
            raise ValueError("Only one of name or transform can be provided")
        
        self.name = name
        self.transform = transform
    
    def __call__(self, data: Any) -> Any:
        if self.transform is not None:
            return self.transform(data)
        if self.name is not None:
            return data[self.name]
        return data

def from_dataset_field(*, name: str = None, transform: Optional[Callable[[Dict[str, Any]], Any]] = None) -> Union[str, Callable[[Dict[str, Any]], Any]]:
    if name is not None and transform is not None:
        raise ValueError("Only one of name or transform can be provided")

    if name is not None:
        return name

    if transform is not None:
        return transform

    raise ValueError("At least one of name or transform must be provided")


def from_llm_response_text() -> str:
    return EVALUATED_LLM_TASK_OUTPUT


def from_agent_output(*, name: str = None, transform: Optional[Callable[[Any], Any]] = None) -> Union[str, Callable[[Any], Any]]:
    if name is not None and transform is not None:
        raise ValueError("Only one of name or transform can be provided")

    if name is not None:
        return lambda agent_output: agent_output[name]

    if transform is not None:
        return transform

    return EVALUATED_LLM_TASK_OUTPUT
