"""Answer "is my AI client actually running a current opik-mcp?" by launching it.

``status`` reports what a config *says*; this reports what it *does*. Only a
launch settles it, because the answer is not a property of the machine: uv reuses
a tool environment only when its compiled wheels match the interpreter it picks
for that launch, so one config can start a months-old build and the published one
on alternate runs (see ``uv_tool``).

Two cheaper-looking checks are wrong, and are not worth reimplementing:

- The MCP ``initialize`` handshake reports the ``mcp`` library's version, not the
  package's — opik-mcp builds ``FastMCP("opik-mcp", ...)`` with no ``version=``.
- ``uvx --from opik-mcp python -c ...`` re-resolves instead of short-circuiting,
  because ``python`` is not an entry point of the installed tool, so it reports a
  current version even on a frozen client.
"""

import dataclasses
import glob
import json
import logging
import os
import pathlib
import re
import subprocess
import urllib.request
from typing import Any, Dict, Final, List, Optional, Tuple

from opik.configurator.mcp import spec as mcp_spec
from opik.configurator.mcp import targets as mcp_targets
from opik.configurator.mcp import uv_tool

LOGGER = logging.getLogger(__name__)

#: A launch has to build or fetch an environment on a cold cache, so this is
#: generous — but bounded, because `opik mcp doctor` must not hang.
LAUNCH_TIMEOUT_SECONDS: Final[int] = 240

PYPI_URL = "https://pypi.org/pypi/opik-mcp/json"
PYPI_TIMEOUT_SECONDS: Final[int] = 15

#: uv logs the ephemeral environment it picked; its absence means it served the
#: launch from the persistent tool install instead.
_EPHEMERAL_ENV = re.compile(r"archive-v0/[A-Za-z0-9_-]+")

SOURCE_INDEX = "index"
SOURCE_TOOL_INSTALL = "tool_install"


@dataclasses.dataclass
class HostDiagnosis:
    display_name: str
    config_path: pathlib.Path
    hosted_url: Optional[str] = None
    launches: Optional[str] = None
    running_version: Optional[str] = None
    source: Optional[str] = None
    problem: Optional[str] = None


@dataclasses.dataclass
class Diagnosis:
    hosts: List[HostDiagnosis]
    latest_version: Optional[str]
    uv_tool_version: Optional[str]


def collect_diagnosis() -> Diagnosis:
    """Inspect every registered host, launching each local server to see what it runs."""
    hosts: List[HostDiagnosis] = []
    for target in mcp_targets.HOST_TARGETS:
        block = mcp_targets.read_registered_block(target)
        if block is None:
            continue
        hosts.append(_diagnose_host(target, block))

    return Diagnosis(
        hosts=hosts,
        latest_version=latest_published_version(),
        uv_tool_version=uv_tool.installed_version(),
    )


def _diagnose_host(
    target: mcp_targets.HostTarget, block: Dict[str, Any]
) -> HostDiagnosis:
    diagnosis = HostDiagnosis(
        display_name=target.display_name, config_path=target.config_path()
    )

    if block.get("type") in ("http", "sse") or "url" in block:
        diagnosis.hosted_url = str(block.get("url", ""))
        return diagnosis

    argv = _launch_argv(block)
    if argv is None:
        diagnosis.problem = "this registration records no command to run"
        return diagnosis

    diagnosis.launches = " ".join(argv)
    version, source, problem = _probe(argv, block)
    diagnosis.running_version = version
    diagnosis.source = source
    diagnosis.problem = problem
    return diagnosis


def _launch_argv(block: Dict[str, Any]) -> Optional[List[str]]:
    """The command a client would spawn, however this host spells it.

    Most hosts split the executable from its arguments; opencode records one
    ``command`` list holding both.
    """
    command = block.get("command")
    if isinstance(command, list):
        return [str(part) for part in command] or None
    if isinstance(command, str):
        return [command, *(str(arg) for arg in block.get("args", []))]
    return None


def _probe(
    argv: List[str], block: Dict[str, Any]
) -> Tuple[Optional[str], Optional[str], Optional[str]]:
    """Run the launch and report ``(version, source, problem)``.

    ``-v`` goes immediately after the executable so uv names the environment it
    chose. Closing stdin makes the stdio server exit as soon as it starts, which
    is all this needs — the interesting output came from uv, before the server
    ever ran.
    """
    environment = {
        **os.environ,
        **(block.get("env") or block.get("environment") or {}),
        # A diagnostic launch is not a session. Without this, running `doctor`
        # would file `opik_mcp_server_started` events that nobody performed, in
        # the very telemetry used to find affected installs.
        "OPIK_MCP_ANALYTICS_ENABLED": "false",
    }

    try:
        result = subprocess.run(
            [argv[0], "-v", *argv[1:]],
            stdin=subprocess.DEVNULL,
            capture_output=True,
            text=True,
            env=environment,
            timeout=LAUNCH_TIMEOUT_SECONDS,
        )
    except subprocess.TimeoutExpired:
        return None, None, f"the server did not start within {LAUNCH_TIMEOUT_SECONDS}s"
    except OSError as error:
        return (
            None,
            None,
            f"could not run {argv[0]} ({error.strerror or error}) — the path recorded "
            f"in this config may no longer exist",
        )

    environments = _EPHEMERAL_ENV.findall(result.stderr + result.stdout)
    if environments:
        env_dir = pathlib.Path.home() / ".cache" / "uv" / environments[-1]
        source = SOURCE_INDEX
    else:
        env_dir = pathlib.Path.home() / ".local/share/uv/tools" / mcp_spec.SERVER_NAME
        source = SOURCE_TOOL_INSTALL
        if not env_dir.exists():
            return None, None, "could not tell which environment uv used"

    version = _installed_version_in(env_dir)
    if version is None:
        return None, source, f"no opik-mcp found in the environment uv used ({env_dir})"
    return version, source, None


def _installed_version_in(env_dir: pathlib.Path) -> Optional[str]:
    matches = glob.glob(
        str(env_dir / "lib" / "python*" / "site-packages" / "opik_mcp-*.dist-info")
    )
    if not matches:
        return None
    return re.sub(r".*opik_mcp-(.*)\.dist-info", r"\1", matches[0])


def latest_published_version() -> Optional[str]:
    """The newest ``opik-mcp`` on PyPI, or ``None`` when the index is unreachable.

    A failure here downgrades the report to "here is what you run" rather than
    failing it: on an airgapped machine the local half is still worth printing.
    """
    try:
        with urllib.request.urlopen(PYPI_URL, timeout=PYPI_TIMEOUT_SECONDS) as response:
            return str(json.load(response)["info"]["version"])
    except Exception as error:
        LOGGER.debug("Could not read the published opik-mcp version: %s", error)
        return None
