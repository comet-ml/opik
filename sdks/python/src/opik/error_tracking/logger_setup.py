import logging

import sentry_sdk.integrations.logging

LOGGER = logging.getLogger(__name__)


def setup_sentry_error_handlers(logger: logging.Logger) -> None:
    """
    Add the two Sentry logging handlers to send error messages with their traceback to Sentry
    for error tracking. This is safe to call even if Sentry is not setup.
    """
    # This handler sends log messages as errors to Sentry
    error_handler = sentry_sdk.integrations.logging.EventHandler(logging.WARNING)

    # This handler track info log message which are sent as breadcrumbs when an error happens
    breadcrumbs_handler = sentry_sdk.integrations.logging.BreadcrumbHandler(
        logging.INFO
    )

    _add_singleton_logging_handler(logger, error_handler)
    _add_singleton_logging_handler(logger, breadcrumbs_handler)


def _add_singleton_logging_handler(
    logger: logging.Logger, handler: logging.Handler
) -> None:
    handler_type = type(handler)
    already_exists = any(
        isinstance(handler, handler_type) for handler in logger.handlers
    )
    if already_exists:
        LOGGER.debug(
            "A %s logging handler is already in the logger %s handler, skipping...",
            handler_type,
            logger,
        )
        return

    logger.addHandler(handler)
