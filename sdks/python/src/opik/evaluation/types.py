from typing import Any, Callable, Dict, Union

LLMTask = Callable[[Dict[str, Any]], Dict[str, Any]]

ScoringKeyMappingType = Dict[str, Union[str, Callable[[Dict[str, Any]], Any]]]
