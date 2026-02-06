import logging
import sys

# Get a logger instance with a unique name for your application
logger = logging.getLogger("trace_analyzer")

# Prevent the logger from propagating messages to the root logger
logger.propagate = False

# Set the logging level (e.g., INFO, DEBUG, ERROR)
logger.setLevel(logging.INFO)

# Check if the logger already has handlers to avoid adding them multiple times
if not logger.handlers:
    # Create a handler to output logs to the console
    handler = logging.StreamHandler(sys.stdout)

    # Create a formatter to define the log message's appearance
    formatter = logging.Formatter(
        "%(asctime)s - %(name)s - %(levelname)s - %(message)s",
    )
    handler.setFormatter(formatter)

    # Add the configured handler to the logger
    logger.addHandler(handler)
