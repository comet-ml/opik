"""Where each AI client looks for user-level skills.

Skills are plain ``SKILL.md`` directories, and the assistants have converged on a
shared user-level location — ``~/.agents/skills`` — rather than each inventing its
own. That is what makes installing them a file operation instead of a per-vendor
integration, and it is why Opik needs no third-party installer CLI to do this.

Verified per assistant:

- **Codex** resolves ``$HOME/.agents/skills`` as a user-scope skill root
  (``codex-rs/ext/skills/src/host_roots.rs``, under ``ConfigLayerSource::User``).
  ``$CODEX_HOME/skills`` is its deprecated predecessor.
- **opencode** loads global skills from ``~/.config/opencode/skills``,
  ``~/.claude/skills`` and ``~/.agents/skills`` (opencode skills documentation).
- **Cursor** loads global skills from ``~/.agents/skills`` *and* ``~/.cursor/skills``
  (and, for compatibility, ``~/.claude/skills`` and ``~/.codex/skills``) — Cursor
  skills documentation.
- **VS Code Copilot** reads ``~/.agents/skills`` or ``~/.copilot/skills`` for
  personal skills that follow you across workspaces — VS Code Agent Skills docs.
- **Claude Code** is the exception: it reads ``~/.claude/skills`` and not the
  shared directory, so it needs a link.

So one write plus one link covers every client Opik supports.

Note that ``~/.agents/skills`` is the *shared* option, not the only one: several
clients also read a vendor-specific global directory, and ``npx skills`` picks
those instead. Both work; writing the shared path once is what lets a single
write cover four of the five. ``.agents/skills`` without the ``~`` is a
*project*-scoped location for most clients, which Opik does not write.
"""

import pathlib
from typing import List, Optional

from opik.configurator.mcp import targets as mcp_targets

SHARED_SKILLS_DIRNAME = ".agents"

# Opik hosts whose skills we know how to place. A host absent from this set is
# simply not offered, rather than being written to a guessed location.
SUPPORTED_HOST_KEYS = ("claude-code", "cursor", "vscode", "codex", "opencode")

# The one host that does not read the shared directory.
LINKED_HOST_KEYS = ("claude-code",)


def shared_skills_dir() -> pathlib.Path:
    """The cross-assistant user-level skills directory."""
    return pathlib.Path.home() / SHARED_SKILLS_DIRNAME / "skills"


def claude_code_skills_dir() -> pathlib.Path:
    return pathlib.Path.home() / ".claude" / "skills"


def reads_shared_dir(host_key: str) -> bool:
    return host_key in SUPPORTED_HOST_KEYS and host_key not in LINKED_HOST_KEYS


def link_dir(host_key: str) -> Optional[pathlib.Path]:
    """Where ``host_key`` needs its own link, or ``None`` if it reads the shared dir."""
    if host_key == "claude-code":
        return claude_code_skills_dir()
    return None


def detected_host_keys() -> List[str]:
    """Supported hosts present on this machine.

    Detection is reused from the MCP installer so a user is asked about one list
    of assistants, not two.
    """
    return [
        target.key
        for target in mcp_targets.detected_targets()
        if target.key in SUPPORTED_HOST_KEYS
    ]


def display_names(host_keys: List[str]) -> List[str]:
    names = []
    for key in host_keys:
        target = mcp_targets.find_target(key)
        names.append(target.display_name if target is not None else key)
    return names
