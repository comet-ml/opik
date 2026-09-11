import functools
import logging
import random

import sentry_sdk

import opik
import opik.config
import opik._logging as _logging

from . import before_send, logger_setup, shutdown_hooks

LOGGER = logging.getLogger(__name__)

SESSION_REPORTING_PROBABILITY = 1.0


@functools.lru_cache
def randomized_should_enable_reporting() -> bool:
    return random.random() <= SESSION_REPORTING_PROBABILITY


@_logging.convert_exception_to_log_message(
    "Error setting up error tracker",
    logger=LOGGER,
    logging_level=logging.DEBUG,
    exc_info=True,
)
def setup_sentry_error_tracker() -> None:
    config = opik.config.OpikConfig()

    sentry_dsn = config.sentry_dsn

    sentry_sdk.init(
        dsn=sentry_dsn,
        integrations=[],
        default_integrations=False,
        before_send=before_send.callback,
        release=opik.__version__,
    )

    root_logger = logging.getLogger("opik")
    logger_setup.setup_sentry_error_handlers(root_logger)

    shutdown_hooks.register_flush_hook()
    shutdown_hooks.register_exception_hook()


@_logging.convert_exception_to_log_message(
    "Error while checking if Sentry is enabled",
    logger=LOGGER,
    return_on_exception=False,
    logging_level=logging.DEBUG,
    exc_info=True,
)
def enabled_in_config() -> bool:
    config = opik.config.OpikConfig()
    enabled = config.sentry_enable

    return enabled
