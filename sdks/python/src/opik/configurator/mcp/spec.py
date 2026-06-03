"""Server-spec abstractions for the Opik MCP install step.

A "spec" describes *what* MCP server to register, independent of *which* host it
is registered with. Host-install code (``targets.py``) consumes specs only
through :class:`McpServerSpec`, so adding a new transport or auth mechanism does
not require changing any per-host install path.

Today only a local stdio server (run via ``uvx opik-mcp``, authenticated with an
API key passed through the environment) is supported. When Opik Cloud hosts a
remote MCP server with browser-based OAuth, add a ``RemoteServerSpec`` here and a
``McpConnectionMode.REMOTE`` branch in ``install._create_server_spec``; the host
targets will keep working unchanged.
"""

import abc
import dataclasses
import enum
from typing import Any, Dict, List

from opik.configurator.mcp import env as mcp_env

SERVER_NAME = "opik-mcp"

_SECRET_ENV_SUFFIXES = ("_KEY", "_TOKEN", "_SECRET", "PASSWORD")
_REDACTED = "***REDACTED***"


def redact_block_for_display(block: Dict[str, Any]) -> Dict[str, Any]:
    """Return a copy of a server block with secret ``env`` values masked.

    Used only for blocks that get logged or printed as manual-setup snippets —
    never for the block actually written to a host config, which needs the real
    values. Masks env keys ending in ``_KEY`` / ``_TOKEN`` / ``_SECRET`` /
    ``PASSWORD`` (e.g. ``OPIK_API_KEY``).
    """
    env = block.get("env")
    if not isinstance(env, dict):
        return block

    redacted_env = {
        key: (_REDACTED if key.upper().endswith(_SECRET_ENV_SUFFIXES) else value)
        for key, value in env.items()
    }
    return {**block, "env": redacted_env}


class McpConnectionMode(enum.Enum):
    """How the configured MCP server connects to Opik.

    Only ``LOCAL_STDIO`` exists today. A ``REMOTE`` member (Opik Cloud-hosted
    server reached over HTTP with browser OAuth) is the planned addition, and it
    applies to Opik Cloud only — localhost and self-hosted deployments always run
    the MCP server locally. See ``install._available_connection_modes``.
    """

    LOCAL_STDIO = "local_stdio"


class McpServerSpec(abc.ABC):
    @abc.abstractmethod
    def to_block(self) -> Dict[str, Any]:
        """The JSON block written under a host's ``mcpServers`` / ``servers`` key."""

    @abc.abstractmethod
    def to_claude_add_args(self) -> List[str]:
        """Arguments appended after ``claude mcp add --scope user``."""


@dataclasses.dataclass
class StdioServerSpec(McpServerSpec):
    command: str
    args: List[str]
    env: mcp_env.McpServerEnv

    def to_block(self) -> Dict[str, Any]:
        return {
            "type": "stdio",
            "command": self.command,
            "args": self.args,
            "env": self.env,
        }

    def to_claude_add_args(self) -> List[str]:
        cli_args = ["--transport", "stdio", SERVER_NAME]
        for key, value in self.env.items():
            cli_args.extend(["--env", f"{key}={value}"])
        cli_args.append("--")
        cli_args.append(self.command)
        cli_args.extend(self.args)
        return cli_args
