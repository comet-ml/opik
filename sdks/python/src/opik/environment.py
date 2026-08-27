import functools
import getpass
import hashlib
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
import opik.url_helpers as url_helpers

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
def get_user_identifier() -> str:
    """
    Approximates "who is running this SDK", for grouping error reports and usage
    analytics by user.

    The workspace name serves as the identifier. If the workspace is the default one,
    or is not really set at all, the identifier is derived from a hash of the hostname
    and username instead. It is not a strict relation - the host machine might change,
    or the user might pass an incorrect workspace - but it is good enough to see how
    many users something affects.

    Blank workspaces fall back rather than being used as-is. Returning "" would put
    every install that has one under a single identifier, making them look like one
    very busy user; surrounding whitespace is stripped for the same reason, so that
    " acme" and "acme" are not two different people.
    """
    workspace = (opik.config.OpikConfig().workspace or "").strip()

    if workspace and workspace != opik.config.OPIK_WORKSPACE_DEFAULT_NAME:
        return workspace

    hashed_part = _hash(get_hostname() + get_user())

    return f"{opik.config.OPIK_WORKSPACE_DEFAULT_NAME}_{hashed_part}"


def is_default_user_identifier(identifier: str) -> bool:
    """True when `get_user_identifier` fell back to the hostname/username hash."""
    return identifier.startswith(f"{opik.config.OPIK_WORKSPACE_DEFAULT_NAME}_")


@functools.lru_cache
def _hash(value: str) -> str:
    return hashlib.sha256(value.encode("utf-8")).hexdigest()


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
