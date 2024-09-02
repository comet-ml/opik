from typing import Dict, Any, Callable
from ..api_objects.dataset import dataset_item

LLMTask = Callable[[dataset_item.DatasetItem], Dict[str, Any]]
