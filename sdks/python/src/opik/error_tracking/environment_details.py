import importlib.metadata
import logging
import random
import string
import importlib
from typing import Any, Dict

import opik

from .. import environment

LOGGER = logging.getLogger(__name__)


def collect_initial_context() -> Dict[str, Any]:
    """
    Returns environment details. It is recommended to collect here only
    the information which we expect to be set before opik import.

    If you need the data which might be set during the script
    execution - consider adding it directly to sentry event dict
    inside before_send function.
    """

    result = {
        "pid": environment.get_pid(),
        "os": environment.get_os(),
        "python_version_verbose": environment.get_python_version_verbose(),
        "session_id": "".join(random.choice(string.ascii_letters) for _ in range(9)),
    }

    installed_packages_details = _get_installed_packages_details()
    result.update(installed_packages_details)

    return result


def collect_initial_tags() -> Dict[str, Any]:
    """
    Tags are similar to context but can be used for filtering and search
    in Sentry.

    If you need the data which might be set during the script
    execution - consider adding it directly to sentry event dict
    inside before_send function.
    """
    result = {
        "os_type": environment.get_os_type(),
        "python_version": environment.get_python_version(),
        "release": opik.__version__,
        "jupyter": environment.in_jupyter(),
        "colab": environment.in_colab(),
        "aws_lambda": environment.in_aws_lambda(),
        "github_actions": environment.in_github_actions(),
        "pytest": environment.in_pytest(),
    }

    return result


def _get_installed_packages_details() -> Dict[str, str]:
    import openai
    import pydantic

    result = {
        "openai": openai.__version__,
        "pydantic": pydantic.__version__,
        "litellm": importlib.metadata.version("litellm"),
    }

    return result
