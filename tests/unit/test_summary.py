import pytest
from testix import *

from comet_llm import summary

NOT_USED = None

@pytest.fixture(autouse=True)
def mock_imports(patch_module):
    patch_module(summary, "LOGGER")


def test_add_log__first_log_reported__name_capitalized():
    tested = summary.Summary()

    with Scenario() as s:
        s.LOGGER.info("%s logged to %s", "The-name", "project-url")
        tested.add_log("project-url", "the-name")

def test_print__happyflow():
    tested = summary.Summary()

    with Scenario() as s:
        s.LOGGER.info("%s logged to %s", "The-name-1", "project-url-1")
        tested.add_log("project-url-1", "the-name-1")
        tested.add_log("project-url-2", "the-name-2")
        tested.increment_failed()
        tested.increment_failed()

        s.LOGGER.info("%d prompts and chains logged to %s", 1, "project-url-1")
        s.LOGGER.info("%d prompts and chains logged to %s", 1, "project-url-2")
        s.LOGGER.info("%d prompts and chains were not logged because of errors", 2)
        tested.print()
