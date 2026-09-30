"""
Details about the run that both Sentry error reports and Segment usage analytics
attach to what they send, so the two describe the same session identically.
"""

import importlib.metadata
import logging
import functools
import os
import random
import string
import importlib
from typing import Any, Dict

from . import environment, package_version

LOGGER = logging.getLogger(__name__)


#: Set by a launcher that reported events of its own before starting this
#: process - today `npx opik`, which posts why the run began and then hands over
#: to `uvx opik`. Honoured so both halves land under one `session_id`; without it
#: each side invents its own and nothing joins them.
SESSION_ID_ENV_VAR = "OPIK_CLI_SESSION_ID"

#: Set by a launcher that started this process - today `npx opik`, which reports
#: why the run began and then hands over to `uvx opik`.
LAUNCHER_ENV_VAR = "OPIK_CLI_LAUNCHER"

#: How this run was entered, reported on every event from the moment it is known.
#: Deliberately NOT part of `collect_context_once`: that is cached on the first
#: event, and the things recorded here are learned later - `opik configure` only
#: hands over to the MCP flow once the user has said yes. Anything set here lands
#: on the events that follow, which is what lets one funnel separate populations
#: that reached it different ways without joining to another command's events.
_RUN_CONTEXT: Dict[str, Any] = {}


def set_run_context(**values: Any) -> None:
    """Record how this run was entered, for every event reported after this."""
    _RUN_CONTEXT.update(values)


def run_context() -> Dict[str, Any]:
    """The run context, including anything a launcher announced through the env.

    Read rather than cached: the point of it is to carry facts learned partway
    through a run, so a cache would freeze it at the first event.
    """
    launcher = os.environ.get(LAUNCHER_ENV_VAR, "").strip()
    if launcher:
        return {"cli_launcher": launcher, **_RUN_CONTEXT}
    return dict(_RUN_CONTEXT)


SESSION_ID_LENGTH = 9


def _session_id() -> str:
    """This process's session, inherited from a launcher when there was one.

    A launcher only ever passes this to a CLI it is about to exec, so the id it
    pins describes one command. `_reset_after_fork` cannot undo an inherited
    value - a forked child re-reads the same variable - which is why nothing sets
    it around long-lived or forking processes.
    """
    inherited = os.environ.get(SESSION_ID_ENV_VAR, "").strip()
    if inherited:
        return inherited

    return "".join(
        random.choice(string.ascii_letters) for _ in range(SESSION_ID_LENGTH)
    )


@functools.lru_cache
def collect_context_once() -> Dict[str, Any]:
    result = {
        "pid": environment.get_pid(),
        "os": environment.get_os(),
        "python_version_verbose": environment.get_python_version_verbose(),
        "session_id": _session_id(),
    }

    installed_packages_details = _get_installed_packages_details()
    result.update(installed_packages_details)

    return result


def _reset_after_fork() -> None:
    """
    `pid` and `session_id` describe one process, and the cache holding them survives
    `fork()`. Without this a child keeps reporting its parent's values, so its error
    reports and its usage events are indistinguishable from the parent's.
    """
    collect_context_once.cache_clear()


if hasattr(os, "register_at_fork"):
    os.register_at_fork(after_in_child=_reset_after_fork)


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
        "release": package_version.VERSION,
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
