"""Install the Opik skill pack into the user's AI assistant(s).

Where the MCP server gives an assistant *tools*, the skill pack gives it the
knowledge of how to use Opik — how to instrument a codebase, wire up an
integration, build a test suite, use ``opik connect``. The two answer different
halves of "my agent is connected to Opik but does not know what to do with it".

There is no third-party installer involved. Skills are ``SKILL.md`` directories,
and the assistants have converged on a shared user-level location, so installing
them is: write the files to ``~/.agents/skills``, then link that into
``~/.claude/skills`` for Claude Code, which is the one host that reads its own
directory instead of the shared one. See ``roots`` for the per-assistant evidence.

That keeps the whole operation inside the Python SDK — no Node, no ``npx``, no
external CLI whose flags can change under us — and makes it work identically on a
machine that has never had a JavaScript toolchain.

Analytics note: as with the MCP installer, the reporting seam is being built on a
separate branch. ``ANALYTICS:`` comments mark the events this flow owes.
"""

import logging
import pathlib
import shutil
from typing import List, Optional

from opik.configurator.skills import manifest as skills_manifest
from opik.configurator.skills import pack as skills_pack
from opik.configurator.skills import roots as skills_roots

LOGGER = logging.getLogger(__name__)


def setup_skills(host_keys: List[str], ref: str = skills_pack.DEFAULT_REF) -> bool:
    """Install the Opik skill pack for ``host_keys``. Returns True on success.

    Never raises: the skill pack is an enhancement, and failing to install it must
    not fail the surrounding configure run.
    """
    supported = [key for key in host_keys if key in skills_roots.SUPPORTED_HOST_KEYS]
    if len(supported) == 0:
        LOGGER.warning(
            "Skipping the Opik skill pack: none of the requested hosts (%s) have a "
            "known skills location.",
            ", ".join(host_keys) or "none",
        )
        # ANALYTICS: skills install skipped, reason="no_supported_host".
        return False

    display = ", ".join(skills_roots.display_names(supported))
    LOGGER.info("Installing the Opik skill pack for %s...", display)

    # ANALYTICS: skills install started, with the host count.
    try:
        pack = skills_pack.download(ref=ref)
    except skills_pack.PackError as error:
        LOGGER.warning("Could not install the Opik skill pack: %s.", error)
        # ANALYTICS: skills install failed, reason="download_failed".
        return False

    shared_dir = skills_roots.shared_skills_dir()
    try:
        shared_dir.mkdir(parents=True, exist_ok=True)
        for name, files in pack.skills.items():
            skills_pack.write_skill(shared_dir, name, files)
    except OSError as error:
        LOGGER.warning(
            "Could not write the Opik skill pack to %s: %s.", shared_dir, error
        )
        # ANALYTICS: skills install failed, reason="write_failed".
        return False

    skills_manifest.write(
        names=pack.names, content_hash=pack.content_hash, ref=pack.ref
    )

    LOGGER.info(
        "Installed the Opik skill pack (%s) in %s.",
        ", ".join(pack.names),
        shared_dir,
    )

    for host_key in supported:
        if skills_roots.reads_shared_dir(host_key):
            continue
        _link_for_host(host_key, pack.names, shared_dir)

    LOGGER.info(
        "Restart your AI host, then ask it to 'add Opik tracing to this project'."
    )
    _warn_on_claude_code_plugin_overlap(supported)
    # ANALYTICS: skills install completed, with the host count and skill names.
    return True


def _link_for_host(host_key: str, names: List[str], shared_dir: pathlib.Path) -> None:
    """Point a host's own skills directory at the shared install."""
    link_root = skills_roots.link_dir(host_key)
    if link_root is None:
        return

    display = ", ".join(skills_roots.display_names([host_key]))
    linked: List[str] = []
    for name in names:
        try:
            link_root.mkdir(parents=True, exist_ok=True)
            _replace_with_link(link_root / name, shared_dir / name)
            linked.append(name)
        except OSError as error:
            LOGGER.warning(
                "Could not link %s into %s for %s: %s. The skill is installed in "
                "%s; link it by hand to use it there.",
                name,
                link_root,
                display,
                error,
                shared_dir,
            )
            # ANALYTICS: skills link failed for this host.
    if linked:
        LOGGER.info("%s: linked %s in %s.", display, ", ".join(linked), link_root)


def _replace_with_link(link_path: pathlib.Path, target: pathlib.Path) -> None:
    """Symlink ``link_path`` to ``target``, falling back to a copy.

    Windows only permits symlinks with Developer Mode or elevation, so a copy is
    the honest fallback there — it costs a stale copy on the next update, which is
    better than refusing to install.
    """
    if link_path.is_symlink() or link_path.exists():
        if link_path.is_dir() and not link_path.is_symlink():
            shutil.rmtree(link_path)
        else:
            link_path.unlink()

    try:
        link_path.symlink_to(target, target_is_directory=True)
    except (OSError, NotImplementedError):
        shutil.copytree(target, link_path)


def _warn_on_claude_code_plugin_overlap(host_keys: List[str]) -> None:
    """Flag the one known duplicate: Claude Code's Opik plugin ships its own skill.

    ``opik-claude-code-plugin`` bundles a skill also called ``opik`` whose content
    has drifted from the one in the pack. Claude Code namespaces plugin skills, so
    both can coexist without breaking — but the assistant then carries two similar
    Opik skills, and the user should know which is which.
    """
    if "claude-code" not in host_keys:
        return
    plugin_skill = (
        pathlib.Path.home()
        / ".claude"
        / "plugins"
        / "marketplaces"
        / "opik"
        / "skills"
        / "opik"
    )
    if not plugin_skill.exists():
        return
    LOGGER.info(
        "Note: the Opik Claude Code plugin also ships an `opik` skill, so Claude "
        "Code now has both. They overlap; remove the plugin's copy with "
        "`/plugin uninstall opik` if you prefer the pack alone."
    )


def uninstall_skills(host_keys: Optional[List[str]] = None) -> List[str]:
    """Remove Opik's skills from the shared directory and any host links.

    Returns the names removed. Only touches skills Opik recorded installing, so a
    hand-made skill that happens to share a name is left alone.
    """
    statuses = [
        status
        for status in skills_manifest.collect_status()
        if status.installed_by_opik
    ]
    removed: List[str] = []
    for status in statuses:
        for host_key in host_keys or skills_roots.LINKED_HOST_KEYS:
            link_root = skills_roots.link_dir(host_key)
            if link_root is None:
                continue
            _remove_path(link_root / status.name)
        _remove_path(status.path)
        removed.append(status.name)

    if removed:
        skills_manifest.write(names=[])
    return removed


def _remove_path(path: pathlib.Path) -> None:
    try:
        if path.is_symlink() or path.is_file():
            path.unlink()
        elif path.is_dir():
            shutil.rmtree(path)
    except OSError:
        LOGGER.debug("Could not remove %s", path, exc_info=True)
