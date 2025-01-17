import logging

from opik.error_tracking import logger_setup


def test_singleton_sentry_handlers():
    """Check that we are only adding sentry handlers once, they are using a singleton object in
    sentry so we don't need to add them multiple time
    """

    test_logger = logging.getLogger("test_singleton_sentry_handlers")
    base_handler = logging.StreamHandler()
    test_logger.addHandler(base_handler)

    assert len(test_logger.handlers) == 1

    # Add sentry handlers, there are two of them
    logger_setup.setup_sentry_error_handlers(test_logger)

    assert len(test_logger.handlers) == 3
    # Make sure the existing handler wasn't removed
    assert base_handler in test_logger.handlers

    # Sentry handlers are already present
    logger_setup.setup_sentry_error_handlers(test_logger)

    assert len(test_logger.handlers) == 3
    # Make sure the existing handler wasn't removed
    assert base_handler in test_logger.handlers
