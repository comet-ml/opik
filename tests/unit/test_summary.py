import pytest

from testix import *
from comet_llm import summary

@pytest.fixture(autouse=True)
def mock_imports(patch_module):
    patch_module(summary, "logs_registry")
    patch_module(summary, "LOGGER")


def _construct():
    with Scenario() as s:
        s.logs_registry.LogsRegistry() >> Fake("registry")
        tested = summary.Summary()
    
    return tested

def test_add_log__registry_empty__report_about_first_log():
    tested = _construct()

    with Scenario() as s:
        s.registry.empty() >> True
        s.LOGGER.info("Prompt logged to %s", "project-url")
        s.registry.register_log("project-url")
        tested.add_log("project-url")


def test_add_log__registry_not_empty__no_additional_logs():
    tested = _construct()

    with Scenario() as s:
        s.registry.empty() >> False
        s.registry.register_log("project-url")
        tested.add_log("project-url")


def test_print__registry_empty__nothing_printed():
    tested = _construct()

    with Scenario() as s:
        s.registry.as_dict() >> {}
        tested.print()


def test_print__happyflow():
    tested = _construct()

    with Scenario() as s:
        s.registry.as_dict() >> {"project-url-1": 5, "project-url-2": 10}
        s.LOGGER.info("%d prompts logged to %s", 5, "project-url-1")
        s.LOGGER.info("%d prompts logged to %s", 10, "project-url-2")
        tested.print()
