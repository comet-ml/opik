"""Find and remove an ``opik-mcp`` left behind as a persistent uv tool install.

SDK 2.0.60 through 2.2.44 tried to warm uv's cache by running ``uv tool install
opik-mcp``. That does not warm a cache: it builds a *persistent* tool
environment, and from then on ``uvx opik-mcp`` resolves to that environment
rather than the index — per ``uv help tool run``, an installed tool is used
"unless a version is requested" or ``--isolated`` is passed. Those clients have
been starting the same build ever since, whatever was published afterwards.

2.2.48 stopped creating new ones, so nothing makes more of these. What is left is
cleaning up the ones already out there, and the registration itself is the wrong
place to do it: writing ``--isolated`` into every config would make every future
user carry a workaround for a condition they cannot have, and would leave the
stale environment on disk regardless. Removing the install fixes the machine
instead, and leaves ``uvx opik-mcp`` meaning what it plainly says.

Removal is offered, never assumed. The original bug was an unannounced write into
someone's environment; deleting from it without asking would be the same mistake
pointed the other way. A user who says no keeps what they have — which is also
the right answer for the rare person who installed a version on purpose.
"""

import logging
import re
import shutil
import subprocess
from typing import Final, Optional, Tuple

LOGGER = logging.getLogger(__name__)

#: Both commands are local directory work, so these only have to survive a uv
#: that hangs rather than a slow network.
COMMAND_TIMEOUT_SECONDS: Final[int] = 30

#: ``uv tool list`` prints one ``<name> v<version>`` line per tool, each followed
#: by its entry points as ``- <command>`` lines. Anchored so an entry point named
#: after the package cannot match.
_TOOL_LINE = re.compile(r"^opik-mcp\s+v(?P<version>\S+)")


def installed_version() -> Optional[str]:
    """The version of any ``opik-mcp`` installed as a uv tool, or ``None``.

    Best-effort: every failure answers "nothing installed". A uv that is missing,
    broken or slow must not take a configure run down with it.
    """
    result = _run_uv("list")
    if result is None:
        return None

    for line in result.stdout.splitlines():
        match = _TOOL_LINE.match(line.strip())
        if match is not None:
            return match.group("version")
    return None


def uninstall() -> Tuple[bool, str]:
    """Remove the ``opik-mcp`` tool install, reporting ``(succeeded, detail)``.

    Only ever called after the user agrees. ``detail`` carries uv's own words on
    failure, because the useful next step ("run it yourself and see") depends on
    what it said.
    """
    result = _run_uv("uninstall")
    if result is None:
        return False, "`uv tool uninstall opik-mcp` could not be run"
    if result.returncode != 0:
        detail = _first_line(result.stderr) or _first_line(result.stdout)
        return (
            False,
            detail or f"`uv tool uninstall opik-mcp` exited {result.returncode}",
        )
    return True, "removed"


def _run_uv(subcommand: str) -> Optional["subprocess.CompletedProcess"]:
    uv_executable = shutil.which("uv")
    if uv_executable is None:
        return None

    command = [uv_executable, "tool", subcommand]
    if subcommand != "list":
        command.append("opik-mcp")

    try:
        result = subprocess.run(
            command,
            capture_output=True,
            text=True,
            stdin=subprocess.DEVNULL,
            timeout=COMMAND_TIMEOUT_SECONDS,
        )
    except (OSError, subprocess.TimeoutExpired) as error:
        LOGGER.debug("Could not run `uv tool %s`: %s", subcommand, error)
        return None

    if subcommand == "list" and result.returncode != 0:
        LOGGER.debug("`uv tool list` exited %s", result.returncode)
        return None
    return result


def _first_line(output: str) -> str:
    for line in (output or "").splitlines():
        if line.strip():
            return line.strip()
    return ""
