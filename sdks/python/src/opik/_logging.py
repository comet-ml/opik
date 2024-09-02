import logging

import sys

from . import config

CONSOLE_MSG_FORMAT = "OPIK: %(message)s"

FILE_MSG_FORMAT = "%(asctime)s OPIK %(levelname)s: %(message)s"


def setup() -> None:
    opik_root_logger = logging.getLogger("opik")
    config_ = config.OpikConfig()

    console_handler = logging.StreamHandler(sys.stdout)
    console_level = config_.console_logging_level
    console_handler.setLevel(console_level)
    console_handler.setFormatter(logging.Formatter(CONSOLE_MSG_FORMAT))

    opik_root_logger.addHandler(console_handler)

    root_level = console_handler.level

    if config_.file_logging_level is not None:
        file_handler = logging.FileHandler(config_.logging_file)
        file_level = config_.file_logging_level
        file_handler.setLevel(file_level)
        file_handler.setFormatter(logging.Formatter(FILE_MSG_FORMAT))
        opik_root_logger.addHandler(file_handler)

        root_level = min(root_level, file_handler.level)

    opik_root_logger.setLevel(level=root_level)
