"""Detect an ``opik-mcp`` left behind as a persistent uv tool install.

SDK 2.0.60 through 2.2.44 warmed uv's cache by running ``uv tool install
opik-mcp``, which does not warm a cache: it builds a *persistent* tool
environment. From then on ``uvx opik-mcp`` resolved to that environment instead
of the index — per ``uv help tool run``, an installed tool is used "unless a
version is requested" or ``--isolated`` is passed — so those machines kept
starting the same build no matter what was published afterwards.

Registering ``uvx --isolated opik-mcp`` (see :data:`spec.PACKAGE_ARGS`) is what
routes past it, so a leftover install no longer decides what the MCP server runs.
It can still take precedence over a bare ``uvx opik-mcp`` the user types
themselves, which is why this is reported rather than ignored — and only
reported, never removed. The original bug was an unannounced change to someone's
environment; silently undoing it would be the same mistake pointed the other way.
"""

import logging
import re
import shutil
import subprocess
from typing import Final, Optional

LOGGER = logging.getLogger(__name__)

#: ``uv tool list`` is a local directory read, so this only has to survive a uv
#: that hangs rather than a slow network.
LIST_TIMEOUT_SECONDS: Final[int] = 10

#: ``uv tool list`` prints one ``<name> v<version>`` line per tool, each followed
#: by its entry points as ``- <command>`` lines. Anchored so an entry point named
#: after the package cannot match.
_TOOL_LINE = re.compile(r"^opik-mcp\s+v(?P<version>\S+)")


def installed_version() -> Optional[str]:
    """The version of any ``opik-mcp`` installed as a uv tool, or ``None``.

    Best-effort: every failure answers "nothing installed". This only drives an
    advisory message, so a uv that is missing, broken, or slow must not take a
    configure run down with it.
    """
    uv_executable = shutil.which("uv")
    if uv_executable is None:
        return None

    try:
        result = subprocess.run(
            [uv_executable, "tool", "list"],
            capture_output=True,
            text=True,
            stdin=subprocess.DEVNULL,
            timeout=LIST_TIMEOUT_SECONDS,
        )
    except (OSError, subprocess.TimeoutExpired) as error:
        LOGGER.debug("Could not read `uv tool list`: %s", error)
        return None

    if result.returncode != 0:
        LOGGER.debug("`uv tool list` exited %s", result.returncode)
        return None

    return _parse_version(result.stdout)


def _parse_version(output: str) -> Optional[str]:
    for line in (output or "").splitlines():
        match = _TOOL_LINE.match(line.strip())
        if match is not None:
            return match.group("version")
    return None
