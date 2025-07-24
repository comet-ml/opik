import importlib.metadata
import logging
import functools
import random
import string
import importlib
from typing import Any, Dict

import opik

from .. import environment

LOGGER = logging.getLogger(__name__)


@functools.lru_cache
def collect_context_once() -> Dict[str, Any]:
    result = {
        "pid": environment.get_pid(),
        "os": environment.get_os(),
        "python_version_verbose": environment.get_python_version_verbose(),
        "session_id": "".join(random.choice(string.ascii_letters) for _ in range(9)),
    }

    installed_packages_details = _get_installed_packages_details()
    result.update(installed_packages_details)

    return result


@functools.lru_cache
def collect_tags_once() -> Dict[str, Any]:
    """
    Some of the tags may be affected by the configurations set by the user
    after opik has been already imported, so we need to collect this data
    as late as possible.
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
        "installation_type": environment.get_installation_type(),
    }

    return result


@functools.lru_cache
def _get_installed_packages_details() -> Dict[str, str]:
    DISTRIBUTION_NAMES = [
        "pydantic",
        "litellm",
        "openai",
        "openai-agents",
        "anthropic",
        "google-adk",
        "google-genai",
        "langchain",
        "langchain-community",
        "langchain-anthropic",
        "langchain-openai",
        "langchain-google-vertexai",
        "langchain-google-genai",
        "crewai",
        "dspy",
        "llama-index",
        "haystack-ai",
    ]
    result = {}

    # `importlib.metadata.version` does not perform actual import of the package,
    # so it's safe to call it here for all packages.
    # Tests showed it takes about 5ms to collect this data.
    for distribution_name in DISTRIBUTION_NAMES:
        try:
            result[distribution_name] = importlib.metadata.version(distribution_name)
        except Exception:
            pass

    return result
