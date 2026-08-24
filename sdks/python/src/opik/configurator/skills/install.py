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

import dataclasses
import logging
import pathlib
import shutil
from typing import Dict, List, Optional, Tuple

from opik.configurator import interactive_helpers
from opik.configurator.skills import manifest as skills_manifest
from opik.configurator.skills import pack as skills_pack
from opik.configurator.skills import roots as skills_roots

LOGGER = logging.getLogger(__name__)


@dataclasses.dataclass
class InstallResult:
    """What an install did, for the caller to report however it renders.

    Returned rather than logged: deciding what the user sees belongs to the
    caller, and mixing the two in here is what made the skill pack land as raw
    log lines in the middle of an otherwise formatted run.
    """

    succeeded: bool
    skills: List[str] = dataclasses.field(default_factory=list)
    shared_dir: Optional[pathlib.Path] = None
    linked: Dict[str, List[str]] = dataclasses.field(default_factory=dict)
    link_errors: Dict[str, str] = dataclasses.field(default_factory=dict)
    error: Optional[str] = None
    plugin_overlap: bool = False


def setup_skills(
    host_keys: List[str],
    ref: str = skills_pack.DEFAULT_REF,
) -> InstallResult:
    """Install the Opik skill pack for ``host_keys``.

    Never raises: the pack is an enhancement, and failing to install it must not
    fail the surrounding configure run. Every outcome is described by the returned
    :class:`InstallResult`.
    """
    # Same rule as the MCP installer: the pack is instruction files the assistant
    # acts on with its own permissions, so it is only written in a session the
    # user is present for. Naming hosts chooses which, not whether.
    if not interactive_helpers.is_interactive():
        # ANALYTICS: skills install skipped, reason="non_interactive".
        return InstallResult(
            succeeded=False,
            error=(
                "the Opik skill pack is only installed from an interactive "
                "terminal; run `opik skills configure` from a shell"
            ),
        )

    supported = [key for key in host_keys if key in skills_roots.SUPPORTED_HOST_KEYS]
    if len(supported) == 0:
        # ANALYTICS: skills install skipped, reason="no_supported_host".
        return InstallResult(
            succeeded=False,
            error=(
                f"none of the requested assistants ({', '.join(host_keys) or 'none'}) "
                "have a known skills location"
            ),
        )

    # ANALYTICS: skills install started, with the host count.
    try:
        pack = skills_pack.download(ref=ref)
    except skills_pack.PackError as error:
        # ANALYTICS: skills install failed, reason="download_failed".
        return InstallResult(succeeded=False, error=str(error))

    shared_dir = skills_roots.shared_skills_dir()
    try:
        shared_dir.mkdir(parents=True, exist_ok=True)
        for name, files in pack.skills.items():
            skills_pack.write_skill(shared_dir, name, files)
    except OSError as error:
        # ANALYTICS: skills install failed, reason="write_failed".
        return InstallResult(
            succeeded=False, error=f"could not write {shared_dir}: {error}"
        )

    skills_manifest.write(
        names=pack.names,
        content_hash=pack.content_hash,
        ref=pack.ref,
        hosts=supported,
    )

    linked: Dict[str, List[str]] = {}
    link_errors: Dict[str, str] = {}
    for host_key in supported:
        if skills_roots.reads_shared_dir(host_key):
            continue
        names, failure = _link_for_host(host_key, pack.names, shared_dir)
        if names:
            linked[host_key] = names
        if failure is not None:
            link_errors[host_key] = failure

    # ANALYTICS: skills install completed, with the host count and skill names.
    return InstallResult(
        succeeded=True,
        skills=pack.names,
        shared_dir=shared_dir,
        linked=linked,
        link_errors=link_errors,
        plugin_overlap=_claude_code_plugin_ships_its_own_skill(supported),
    )


def _link_for_host(
    host_key: str, names: List[str], shared_dir: pathlib.Path
) -> Tuple[List[str], Optional[str]]:
    """Point a host's own skills directory at the shared install.

    Returns the skills linked and the first failure, if any — facts for the caller
    to word, rather than prose written here.
    """
    link_root = skills_roots.link_dir(host_key)
    if link_root is None:
        return [], None

    linked: List[str] = []
    failure: Optional[str] = None
    for name in names:
        try:
            link_root.mkdir(parents=True, exist_ok=True)
            _replace_with_link(link_root / name, shared_dir / name)
            linked.append(name)
        except OSError as error:
            # ANALYTICS: skills link failed for this host.
            LOGGER.debug("Could not link %s into %s", name, link_root, exc_info=True)
            if failure is None:
                failure = (
                    f"could not link into {link_root} ({error}); the skills are "
                    f"installed in {shared_dir}"
                )
    return linked, failure


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


def _claude_code_plugin_ships_its_own_skill(host_keys: List[str]) -> bool:
    """Whether Claude Code will now carry two overlapping ``opik`` skills.

    ``opik-claude-code-plugin`` bundles a skill also called ``opik`` whose content
    has drifted from the one in the pack. Claude Code namespaces plugin skills, so
    both can coexist without breaking — but the assistant then carries two similar
    Opik skills, and the user should be told which is which, by whoever is doing
    the telling.
    """
    if "claude-code" not in host_keys:
        return False
    return (
        pathlib.Path.home()
        / ".claude"
        / "plugins"
        / "marketplaces"
        / "opik"
        / "skills"
        / "opik"
    ).exists()


@dataclasses.dataclass
class UpdateResult:
    """What ``update_skills`` did, so the caller can report it precisely."""

    changed: bool
    detail: str
    added: List[str] = dataclasses.field(default_factory=list)
    removed: List[str] = dataclasses.field(default_factory=list)


def update_skills(ref: str = skills_pack.DEFAULT_REF) -> UpdateResult:
    """Refresh an existing install, rewriting only if the pack actually changed.

    Compares the downloaded pack against the content hash recorded at install
    time, which is the only way to answer "is this current?" for a directory of
    Markdown files. Skills the pack has dropped are removed, so a rename upstream
    does not leave the old name behind for the assistant to keep reading.
    """
    previous = [
        status.name
        for status in skills_manifest.collect_status()
        if status.installed_by_opik
    ]
    if not previous:
        return UpdateResult(
            changed=False,
            detail=(
                "no Opik-installed skills found. Run `opik skills configure` to "
                "install them."
            ),
        )

    try:
        pack = skills_pack.download(ref=ref)
    except skills_pack.PackError as error:
        return UpdateResult(changed=False, detail=str(error))

    if pack.content_hash == skills_manifest.recorded_content_hash():
        return UpdateResult(
            changed=False,
            detail=f"already up to date ({pack.content_hash[:12]})",
        )

    shared_dir = skills_roots.shared_skills_dir()
    try:
        shared_dir.mkdir(parents=True, exist_ok=True)
        for name, files in pack.skills.items():
            skills_pack.write_skill(shared_dir, name, files)
    except OSError as error:
        return UpdateResult(
            changed=False, detail=f"could not write {shared_dir}: {error}"
        )

    removed = sorted(set(previous) - set(pack.names))
    added = sorted(set(pack.names) - set(previous))

    hosts = skills_manifest.recorded_hosts()
    if hosts is None:
        # Manifest predates the `hosts` field: re-link wherever a link already is.
        hosts = [
            host_key
            for host_key in skills_roots.LINKED_HOST_KEYS
            if _has_any_link(host_key, previous)
        ]

    for name in removed:
        _remove_path(shared_dir / name)
        for host_key in skills_roots.LINKED_HOST_KEYS:
            link_root = skills_roots.link_dir(host_key)
            if link_root is not None:
                _remove_path(link_root / name)

    for host_key in hosts:
        if skills_roots.reads_shared_dir(host_key):
            continue
        _link_for_host(host_key, pack.names, shared_dir)

    skills_manifest.write(
        names=pack.names,
        content_hash=pack.content_hash,
        ref=pack.ref,
        hosts=hosts,
    )

    return UpdateResult(
        changed=True,
        detail=f"updated to {pack.content_hash[:12]}",
        added=added,
        removed=removed,
    )


def _has_any_link(host_key: str, names: List[str]) -> bool:
    link_root = skills_roots.link_dir(host_key)
    if link_root is None:
        return False
    return any((link_root / name).exists() for name in names)


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
