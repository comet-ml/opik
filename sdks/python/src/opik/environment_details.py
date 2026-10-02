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


#: Set by a launcher that reported events before starting this process (today
#: `npx opik`), so both halves of the run share one `session_id`.
SESSION_ID_ENV_VAR = "OPIK_CLI_SESSION_ID"

#: Set by a launcher that started this process (today `npx opik`).
LAUNCHER_ENV_VAR = "OPIK_CLI_LAUNCHER"


def _take_from_launcher(name: str) -> str:
    """A value the launcher handed this process, removed from the environment.

    Removed at import so nothing this process starts — the agent the handoff
    execs into, the app `opik run` starts — reports this run as its own.
    """
    return os.environ.pop(name, "").strip()


_INHERITED_SESSION_ID = _take_from_launcher(SESSION_ID_ENV_VAR)
_LAUNCHER = _take_from_launcher(LAUNCHER_ENV_VAR)

#: How this run was entered. Not in `collect_context_once`, which is cached on
#: the first event: this is learned later in the run.
_RUN_CONTEXT: Dict[str, str] = {}


def set_run_context(**values: str) -> None:
    """Record how this run was entered, for every event enqueued after this.

    Process-global and never cleared: call it only from a CLI entry point that
    owns the process.
    """
    # Replaced, not mutated, so a reader on another thread always copies a
    # complete snapshot.
    global _RUN_CONTEXT
    _RUN_CONTEXT = {**_RUN_CONTEXT, **values}


def run_context() -> Dict[str, Any]:
    """The run context, including the launcher. Not cached: it changes mid-run."""
    if _LAUNCHER:
        return {"cli_launcher": _LAUNCHER, **_RUN_CONTEXT}
    return dict(_RUN_CONTEXT)


SESSION_ID_LENGTH = 9


def _session_id() -> str:
    """This process's session, inherited from a launcher when there was one."""
    if _INHERITED_SESSION_ID:
        return _INHERITED_SESSION_ID

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

    That includes an id inherited from a launcher, which named the parent.
    """
    global _INHERITED_SESSION_ID
    _INHERITED_SESSION_ID = ""
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
