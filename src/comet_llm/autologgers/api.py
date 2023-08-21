import comet_llm.config

from ..import_hooks import registry, finder
from .openai import patcher as openai_patcher


def patch() -> None:
    if comet_llm.config.autologging_enabled():
        registry_ = registry.Registry()

        openai_patcher.patch(registry_)

        module_finder = finder.CometFinder(registry_)
        module_finder.hook_into_import_system()
