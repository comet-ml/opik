import logging
from unittest.mock import patch

import pytest

from opik.api_objects import helpers
from opik.config import OPIK_PROJECT_DEFAULT_NAME
from opik._logging import LOG_ONCE_CACHE

WARNING_MESSAGE = (
    'No project name configured. Traces are being logged to "Default Project".\n'
    "Set OPIK_PROJECT_NAME environment variable or pass project_name to the Opik client\n"
    "to log to a specific project.\n"
    "See https://www.comet.com/docs/opik/tracing/sdk_configuration"
)


@pytest.fixture(autouse=True)
def _clear_log_once_cache():
    LOG_ONCE_CACHE.discard(WARNING_MESSAGE)
    yield


class TestResolveProjectName:
    def test_explicit_project_name_returned(self):
        result = helpers.resolve_project_name(
            explicitly_passed_value="My Project",
            value_from_config=OPIK_PROJECT_DEFAULT_NAME,
        )
        assert result == "My Project"

    def test_explicit_default_project_name_no_warning(self):
        with patch(
            "opik.api_objects.helpers.opik_logging.log_once_at_level"
        ) as mock_log:
            result = helpers.resolve_project_name(
                explicitly_passed_value=OPIK_PROJECT_DEFAULT_NAME,
                value_from_config=OPIK_PROJECT_DEFAULT_NAME,
            )
        assert result == OPIK_PROJECT_DEFAULT_NAME
        mock_log.assert_not_called()

    def test_context_project_returned(self):
        result = helpers.resolve_project_name(
            explicitly_passed_value=None,
            value_from_config=OPIK_PROJECT_DEFAULT_NAME,
            value_from_context="Context Project",
        )
        assert result == "Context Project"

    def test_context_project_no_warning(self):
        with patch(
            "opik.api_objects.helpers.opik_logging.log_once_at_level"
        ) as mock_log:
            helpers.resolve_project_name(
                explicitly_passed_value=None,
                value_from_config=OPIK_PROJECT_DEFAULT_NAME,
                value_from_context="Context Project",
            )
        mock_log.assert_not_called()

    def test_default_fallback_warns(self):
        with patch(
            "opik.api_objects.helpers.opik_logging.log_once_at_level"
        ) as mock_log:
            result = helpers.resolve_project_name(
                explicitly_passed_value=None,
                value_from_config=OPIK_PROJECT_DEFAULT_NAME,
            )
        assert result == OPIK_PROJECT_DEFAULT_NAME
        mock_log.assert_called_once_with(
            logging_level=logging.WARNING,
            message=WARNING_MESSAGE,
            logger=helpers.LOGGER,
        )

    def test_non_default_config_project_no_warning(self):
        with patch(
            "opik.api_objects.helpers.opik_logging.log_once_at_level"
        ) as mock_log:
            result = helpers.resolve_project_name(
                explicitly_passed_value=None,
                value_from_config="Custom Project",
            )
        assert result == "Custom Project"
        mock_log.assert_not_called()
