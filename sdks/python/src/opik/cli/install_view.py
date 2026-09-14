"""The ``rich`` rendering of the MCP install, used by ``opik mcp configure``.

Kept in the CLI layer on purpose: ``configurator.mcp.install`` is reachable from
``opik.configure()``, which is a library call and must not take over someone's
stdout. See ``configurator.mcp.view`` for the injection point and the
logger-based default.
"""

import contextlib
import pathlib
from typing import Iterator, List, Optional

import rich.console
from rich import padding, table, text

from opik.cli import selector
from opik.configurator.mcp import view as mcp_view
from opik.configurator.skills import install as skills_install
from opik.configurator.skills import roots as skills_roots

console = rich.console.Console()


def _collapse_home(message: str) -> str:
    """Shorten any absolute home paths inside a message.

    Failure details are assembled with full paths so a log line is unambiguous,
    but on a narrow terminal one absolute path wraps over three lines and buries
    the actual instruction.
    """
    home = str(pathlib.Path.home())
    return message.replace(home, "~") if home else message


def _join(names: List[str]) -> str:
    """ "a", "a and b", "a, b and c" — a list a person would read aloud."""
    if len(names) <= 1:
        return "".join(names)
    return f"{', '.join(names[:-1])} and {names[-1]}"


_KEY_STYLE = "cyan"
_FIELDS_INDENT = (0, 0, 0, 4)


class RichInstallView(mcp_view.InstallView):
    def plan(
        self,
        deployment: str,
        transport: str,
        targets: List[mcp_view.PlannedTarget],
        needs_sign_in: bool = False,
    ) -> None:
        self._needs_sign_in = needs_sign_in
        console.print()
        console.print(text.Text("Opik MCP server setup", style="bold"))

        grid = table.Table.grid(padding=(0, 2))
        grid.add_column(style=_KEY_STYLE, no_wrap=True)
        grid.add_column(overflow="fold")
        grid.add_row("Deployment", deployment)
        grid.add_row("Connection", transport)
        console.print(padding.Padding(grid, _FIELDS_INDENT, expand=False))

        console.print()
        # Consent is only meaningful if the user can see what will change; these
        # are files owned by other tools.
        console.print(text.Text("Will update", style="bold"))
        files = table.Table.grid(padding=(0, 2))
        files.add_column(style=_KEY_STYLE, no_wrap=True)
        files.add_column(overflow="fold", style="dim")
        for target in targets:
            files.add_row(target.display_name, target.location)
        console.print(padding.Padding(files, _FIELDS_INDENT, expand=False))
        console.print()

    @contextlib.contextmanager
    def step(self, description: str) -> Iterator[None]:
        # `console.status` degrades to a single printed line when stdout is not a
        # terminal, so this is safe in CI and when piped to a file.
        with console.status(f"[dim]{description}...[/dim]", spinner="dots"):
            yield

    def results(self, results: List[mcp_view.TargetResult]) -> None:
        # One grid for every row, so the host column lines up. A row per grid
        # aligns each row against itself and nothing else.
        grid = table.Table.grid(padding=(0, 2))
        grid.add_column(no_wrap=True)
        grid.add_column(style=_KEY_STYLE, no_wrap=True)
        grid.add_column(overflow="fold")
        for result in results:
            if result.succeeded:
                # The plan block already showed the path; repeating it here just
                # wraps and pushes the outcome off the line.
                grid.add_row(
                    text.Text("✓", style="green"),
                    result.display_name,
                    text.Text(result.short, style="dim"),
                )
            else:
                grid.add_row(
                    text.Text("✗", style="red"),
                    result.display_name,
                    text.Text(_collapse_home(result.detail), style="yellow"),
                )
        console.print(padding.Padding(grid, (0, 0, 0, 2), expand=False))

    def verification(self, succeeded: bool, detail: str) -> None:
        # Its own block: it reports on the connection, not on a host, and sharing
        # the grid above would align two things that are not the same kind.
        console.print()
        row = table.Table.grid(padding=(0, 2))
        row.add_column(no_wrap=True)
        row.add_column(style=_KEY_STYLE, no_wrap=True)
        row.add_column(overflow="fold")
        if succeeded:
            row.add_row(text.Text("✓", style="green"), "Verified", text.Text(detail))
        else:
            row.add_row(
                text.Text("✗", style="red"),
                "Not working",
                text.Text(detail, style="yellow"),
            )
        console.print(padding.Padding(row, (0, 0, 0, 2), expand=False))

    def done(self, components: List[str], assistants: List[str]) -> None:
        console.print()
        console.print(
            text.Text.assemble(("✓ ", "green bold"), ("Done", "bold")),
        )
        grid = table.Table.grid(padding=(0, 2))
        grid.add_column(style=_KEY_STYLE, no_wrap=True)
        grid.add_column(overflow="fold")
        grid.add_row("Set up", _join(components) or "nothing")
        grid.add_row("For", _join(assistants) or "your AI client")
        grid.add_row(
            "Next",
            text.Text.assemble(
                ("Restart ", ""),
                ("them" if len(assistants) > 1 else "it", "bold"),
                (", then ask ", ""),
                ('"list my Opik projects via Opik MCP"', "green"),
            ),
        )
        console.print(padding.Padding(grid, _FIELDS_INDENT, expand=False))
        # Last, because it is the one thing here the user may still have to act
        # on, and it should not sit between them and the prompt to try.
        if self._needs_sign_in:
            console.print()
            console.print(
                padding.Padding(
                    text.Text.assemble(
                        ("Signing in: ", "bold"),
                        (mcp_view.SIGN_IN_HINT, "dim"),
                    ),
                    _FIELDS_INDENT,
                )
            )
        console.print()

    def skipped(self, message: str) -> None:
        console.print()
        console.print(text.Text(message, style="dim"))
        console.print()

    def problem(self, message: str) -> None:
        console.print()
        console.print(text.Text(_collapse_home(message), style="yellow"))
        console.print()

    def choose_hosts(
        self,
        title: str,
        candidates: List[mcp_view.HostChoice],
        preselected: List[str],
    ) -> Optional[List[str]]:
        # A one-item list is not worth arrow keys; and a terminal that cannot host
        # a picker still gets the inherited numbered menu rather than an error.
        if len(candidates) == 1 or not selector.is_supported():
            return mcp_view.numbered_menu(title, candidates)

        return selector.multiselect(
            title=title,
            choices=[
                selector.Choice(key=c.key, label=c.label, hint=c.hint)
                for c in candidates
            ],
            preselected=preselected,
        )

    def note(self, message: str) -> None:
        console.print(padding.Padding(text.Text(message, style="dim"), (0, 0, 0, 2)))


def render_skill_pack(
    result: skills_install.InstallResult, view: mcp_view.InstallView
) -> bool:
    """Report a skill-pack install. Returns whether it succeeded."""
    if not result.succeeded:
        view.problem(f"Could not install the Opik skill pack: {result.error}.")
        return False

    view.results(
        [
            mcp_view.TargetResult(
                display_name="Skill pack",
                detail=f"{', '.join(result.skills)} in {result.shared_dir}",
                succeeded=True,
                summary=", ".join(result.skills),
            )
        ]
    )
    for host_key, message in result.link_errors.items():
        label = ", ".join(skills_roots.display_names([host_key]))
        view.problem(f"{label}: {message}")
    if result.plugin_overlap:
        view.note(
            "The Opik Claude Code plugin also ships an `opik` skill, so Claude "
            "Code now has both. Remove the plugin's copy with "
            "`/plugin uninstall opik` if you prefer the pack alone."
        )
    return True
