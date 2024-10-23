from typing import Dict, Any, Callable

LLMTask = Callable[[Dict[str, Any]], Dict[str, Any]]
