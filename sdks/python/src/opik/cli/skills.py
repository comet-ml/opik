"""`opik skills` commands for managing the Opik agent skill pack."""

import logging
from typing import List, Optional, Tuple

import click

from opik.cli import selector
from opik.cli import status_view
from opik.configurator import skills as skills_installer
from opik.configurator.skills import manifest as skills_manifest
from opik.configurator.skills import roots as skills_roots

LOGGER = logging.getLogger(__name__)

HOST_ALL = "all"
HOST_KEYS: List[str] = list(skills_roots.SUPPORTED_HOST_KEYS)


def _resolve_host_keys(hosts: Tuple[str, ...]) -> Optional[List[str]]:
    """Turn ``--host`` values into host keys, or ``None`` when none were named."""
    if len(hosts) == 0:
        return None

    if HOST_ALL in hosts:
        detected = skills_roots.detected_host_keys()
        if len(detected) == 0:
            raise click.ClickException(
                "`--host all` found no supported AI host on this machine. Name one "
                f"explicitly instead: {', '.join(HOST_KEYS)}."
            )
        return detected

    return list(dict.fromkeys(hosts))


def _sentence(text: str) -> str:
    """End with exactly one full stop, whatever the message already carries."""
    return text if text.endswith(".") else f"{text}."


def _ask_which_hosts(detected: List[str]) -> Optional[List[str]]:
    """Which detected assistants to install for.

    Asked rather than assumed: the skill pack is guidance the assistant will act
    on, and a user may well want it in the editor they use for Opik work and not
    in every assistant on the machine.
    """
    if len(detected) == 1 or not selector.is_supported():
        # One candidate needs no list, and a terminal that cannot host a picker
        # would otherwise be stuck; both fall back to a plain confirmation.
        names = ", ".join(skills_roots.display_names(detected))
        if click.confirm(f"Install the Opik skill pack for {names}?", default=True):
            return detected
        return []

    return selector.multiselect(
        title="Which AI assistants should the Opik skill pack be installed for?",
        choices=[
            selector.Choice(key=key, label=label)
            for key, label in zip(detected, skills_roots.display_names(detected))
        ],
        preselected=detected,
    )


@click.group(name="skills")
def skills() -> None:
    """Manage the Opik agent skill pack."""


@skills.command(name="configure")
@click.option(
    "--host",
    "hosts",
    multiple=True,
    type=click.Choice(HOST_KEYS + [HOST_ALL], case_sensitive=False),
    help="AI host to install the skill pack for. Repeatable, or pass `all` for "
    "every host detected on this machine. Defaults to every detected host.",
)
def configure(hosts: Tuple[str, ...]) -> None:
    """Install the Opik skill pack into your AI assistant(s).

    The pack teaches your assistant how to instrument code with Opik, wire up
    framework integrations, build test suites, and use `opik connect`. It is
    separate from the MCP server, which gives the assistant tools rather than
    knowledge — most people want both (`opik mcp configure`).

    Skills install for your user account, not a project, so this can be run from
    anywhere. No Opik credentials are required.
    """
    host_keys = _resolve_host_keys(hosts)

    if host_keys is None:
        detected = skills_roots.detected_host_keys()
        if len(detected) == 0:
            raise click.ClickException(
                "No supported AI host was detected. Name one explicitly: "
                f"`opik skills configure --host {HOST_KEYS[0]}`."
            )
        host_keys = _ask_which_hosts(detected)
        if host_keys is None:
            raise click.ClickException("Cancelled; nothing was installed.")
        if len(host_keys) == 0:
            click.echo("No assistants selected; nothing was installed.")
            return

    if not skills_installer.setup_skills(host_keys):
        raise click.ClickException(
            "The Opik skill pack was not installed — see the messages above."
        )


@skills.command(name="update")
def update() -> None:
    """Update the installed Opik skill pack to the latest published version.

    Rewrites only if the pack actually changed, so running this on a schedule is
    cheap. Skills the pack has dropped are removed rather than left behind for
    your assistant to keep reading.
    """
    result = skills_installer.update_skills()

    if not result.changed:
        click.echo(f"Opik skill pack: {_sentence(result.detail)}")
        return

    click.echo(f"Opik skill pack {_sentence(result.detail)}")
    if result.added:
        click.echo(f"  added:   {', '.join(result.added)}")
    if result.removed:
        click.echo(f"  removed: {', '.join(result.removed)}")
    click.echo("Restart your AI host to pick up the change.")


@skills.command(name="remove")
@click.option(
    "--yes",
    "-y",
    is_flag=True,
    default=False,
    help="Skip the confirmation prompt.",
)
def remove(yes: bool) -> None:
    """Remove the Opik skill pack from your user account.

    Only removes skills this CLI installed, so a hand-written skill that happens
    to share a name is left alone.
    """
    installed = [
        status.name
        for status in skills_manifest.collect_status()
        if status.installed_by_opik
    ]
    if not installed:
        click.echo("No Opik-installed skills found; nothing to remove.")
        return

    if not yes and not click.confirm(
        f"Remove {', '.join(installed)} from {skills_roots.shared_skills_dir()}?",
        default=False,
    ):
        click.echo("Left the Opik skill pack in place.")
        return

    removed = skills_installer.uninstall_skills()
    click.echo(f"Removed {', '.join(removed)}." if removed else "Nothing was removed.")


@skills.command(name="status")
def status() -> None:
    """Show which Opik skills are installed for your user account."""
    status_view.render_skills_status(
        statuses=skills_manifest.collect_status(),
        shared_dir=skills_roots.shared_skills_dir(),
    )
