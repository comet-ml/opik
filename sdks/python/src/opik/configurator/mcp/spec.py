"""Server-spec abstractions for the Opik MCP install step.

A "spec" describes *what* MCP server to register, independent of *which* host it
is registered with. Host-install code (``targets.py``) consumes specs only
through :class:`McpServerSpec`, so adding a new transport or auth mechanism does
not require changing any per-host install path.

Two transports are supported:

- :class:`RemoteServerSpec` — the Opik-hosted MCP server reached over HTTP, with
  the AI host handling browser-based OAuth. Used when the configured deployment
  advertises an MCP auth server (see ``detection.detect_hosted_mcp_server``).
- :class:`StdioServerSpec` — a local server run via ``uvx opik-mcp`` and
  authenticated with an API key passed through the environment. Used as the
  fallback when no hosted server is available.
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

    ``REMOTE`` is the Opik-hosted server reached over HTTP with browser OAuth; it
    is used whenever the configured deployment advertises an MCP auth server.
    ``LOCAL_STDIO`` is the ``uvx opik-mcp`` fallback used otherwise. The choice is
    made by probing the deployment, not by deployment type — see
    ``install.setup_mcp_server`` and ``detection.detect_hosted_mcp_server``.
    """

    REMOTE = "remote"
    LOCAL_STDIO = "local_stdio"


class McpServerSpec(abc.ABC):
    @abc.abstractmethod
    def to_block(self) -> Dict[str, Any]:
        """The JSON block written under a host's ``mcpServers`` / ``servers`` key."""

    @abc.abstractmethod
    def to_claude_add_args(self) -> List[str]:
        """Arguments appended after ``claude mcp add --scope user``."""


@dataclasses.dataclass
class RemoteServerSpec(McpServerSpec):
    """The Opik-hosted MCP server, reached over HTTP.

    No credentials are written into the host config: the AI host performs the
    OAuth browser flow against the deployment's auth server on first connection.
    """

    url: str

    def to_block(self) -> Dict[str, Any]:
        return {"type": "http", "url": self.url}

    def to_claude_add_args(self) -> List[str]:
        return ["--transport", "http", SERVER_NAME, self.url]


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
