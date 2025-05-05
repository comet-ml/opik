import functools
import getpass
import logging
import os
import platform
import socket
import sys
from importlib import metadata
from typing import Dict, Literal
import tqdm
from tqdm.utils import Comparable

import opik.config
from opik import url_helpers

LOGGER = logging.getLogger(__name__)


def get_pid() -> int:
    return os.getpid()


@functools.lru_cache
def get_hostname() -> str:
    return socket.gethostname()


@functools.lru_cache
def get_user() -> str:
    try:
        return getpass.getuser()
    except Exception:
        LOGGER.debug(
            "Unknown exception getting the user from the system", exc_info=True
        )
        return "unknown"


@functools.lru_cache
def get_os() -> str:
    return platform.platform(aliased=True)


@functools.lru_cache
def get_os_type() -> str:
    return platform.system()


@functools.lru_cache
def get_python_version_verbose() -> str:
    return sys.version


@functools.lru_cache
def get_python_version() -> str:
    return platform.python_version()


def in_pytest() -> bool:
    return "pytest" in sys.modules


@functools.lru_cache
def in_github_actions() -> bool:
    return "GITHUB_ACTIONS" in os.environ


@functools.lru_cache
def in_aws_lambda() -> bool:
    return "LAMBDA_TASK_ROOT" in os.environ


def get_installation_type() -> Literal["cloud", "self-hosted", "local"]:
    config = opik.config.OpikConfig()
    url_override = config.url_override
    if url_helpers.get_base_url(url_override) == url_helpers.get_base_url(
        opik.config.OPIK_URL_CLOUD
    ):
        return "cloud"

    if "localhost" in url_override:
        return "local"

    return "self-hosted"


@functools.lru_cache
def in_jupyter() -> bool:
    """
    Check to see if code is running in a Jupyter environment,
    including jupyter notebook, lab, or console.
    """
    try:
        import IPython
    except Exception:
        return False

    ipy = IPython.get_ipython()
    if ipy is None or not hasattr(ipy, "kernel"):
        return False
    else:
        return True


@functools.lru_cache
def in_ipython() -> bool:
    """
    Check to see if code is running in an IPython environment.
    """
    try:
        import IPython
    except Exception:
        return False

    ipy = IPython.get_ipython()
    if ipy is None:
        return False
    else:
        return True


@functools.lru_cache
def in_colab() -> bool:
    """
    Check to see if code is running in Google colab.
    """
    try:
        import IPython
    except Exception:
        return False

    ipy = IPython.get_ipython()
    return "google.colab" in str(ipy)


@functools.lru_cache
def get_installed_packages() -> Dict[str, str]:
    """
    Retrieve a dictionary of installed packages with their versions.
    """
    installed_packages = {
        pkg.metadata["Name"]: pkg.version for pkg in metadata.distributions()
    }
    return installed_packages


def get_tqdm_for_current_environment() -> Comparable:
    """
    Get a tqdm progress bar for your environment.
    """
    if in_jupyter() or in_colab():
        return tqdm.tqdm_notebook
    else:
        return tqdm.tqdm
