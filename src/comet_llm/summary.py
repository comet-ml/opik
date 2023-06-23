import logging
from . import logs_registry

LOGGER = logging.getLogger(__file__)

class Summary:
    def __init__(self) -> None:
        self._registry = logs_registry.LogsRegistry()
    
    def add_log(self, project_url: str) -> None:
        if self._registry.empty():
            LOGGER.info("Prompt logged to %s", project_url)

        self._registry.register_log(project_url)
    
    def print(self) -> None:
        registry_items = self._registry.as_dict().items()

        for project, logs_amount in registry_items:
            LOGGER.info("%d prompts logged to %s", logs_amount, project)