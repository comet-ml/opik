"""The ``rich`` rendering of the MCP install, used by ``opik mcp configure``.

Kept in the CLI layer on purpose: ``configurator.mcp.install`` is reachable from
``opik.configure()``, which is a library call and must not take over someone's
stdout. See ``configurator.mcp.view`` for the injection point and the
logger-based default.
"""

import contextlib
import pathlib
import textwrap
from typing import Iterator, List, Optional, Tuple

import rich.console
from rich import padding, table, text

from opik.cli import selector
from opik.configurator import consent
from opik.configurator.mcp import view as mcp_view
from opik.configurator.skills import install as skills_install
from opik.configurator.skills import roots as skills_roots

console = rich.console.Console()

#: Keys of the synthetic rows in the host picker. Not host keys, and cannot
#: collide with one: `mcp_targets.HOST_KEYS` are plain names like `claude-code`.
_ALL = "__all__"
_SKIP = "__skip__"


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


def render_numbered_choices(question: str, choices: List[Tuple[str, str, str]]) -> None:
    """A numbered question: a headline, then ``number``, ``label``, ``hint`` rows.

    The caller does the reading. This only draws the question, so the answers a
    command accepts never depend on how it looked — which is what keeps a
    prettier prompt from breaking anything driving the CLI from a script.

    A grid rather than hand-padded strings: it keeps the hint inside its own
    column, so a narrow terminal folds the hint under itself instead of losing
    the indent. Every cell is a ``Text``, which turns off ``rich``'s markup and
    its number highlighting — ``1`` is a list marker here, not a value to
    colour, and ``(default)`` is not a tuple.
    """
    console.print()
    console.print(text.Text(question, style="bold"))

    # The hint is the first thing to go when the terminal is small. Folding it
    # into whatever is left turns "free to start" into five one-word lines, which
    # is worse than not showing it: the numbers and the names are what the answer
    # is made of, and they are what the column budget buys first.
    label_width = max(len(label) for _, label, _ in choices)
    show_hints = console.width >= _FIELDS_INDENT[3] + 5 + label_width + 20

    grid = table.Table.grid(padding=(0, 2))
    grid.add_column(style=_KEY_STYLE, no_wrap=True, justify="right")
    grid.add_column(no_wrap=True)
    if show_hints:
        grid.add_column(overflow="fold", style="dim")
    for number, label, hint in choices:
        row = [text.Text(number), text.Text(label)]
        if show_hints:
            row.append(text.Text(hint))
        grid.add_row(*row)
    console.print(padding.Padding(grid, _FIELDS_INDENT, expand=False))
    console.print()


def can_pick() -> bool:
    """Whether this terminal can host an interactive picker.

    Asked by the caller so that "no picker here" and "the user cancelled" stay
    two different answers: the first falls back to a plain prompt, the second
    aborts the command, and collapsing them into one ``None`` turned Ctrl-C into
    "ask me again".
    """
    return selector.is_supported()


def choose_one_numbered(
    question: str, choices: List[Tuple[str, str, str]]
) -> Optional[str]:
    """Pick one row by arrow keys or by typing its number. ``None`` = cancelled.

    Only call this when :func:`can_pick` is true. The plain-prompt fallback is a
    question with an input contract, and that belongs to the command asking it
    rather than to the renderer.
    """
    return selector.choose_one(
        question,
        [
            selector.Choice(key=number, label=label, hint=hint)
            for number, label, hint in choices
        ],
    )


def render_mcp_intro() -> None:
    """What the MCP step is, before either command asks about it.

    Shared by ``opik configure`` and ``opik mcp configure`` so the two explain
    themselves identically: they write into the same files, and only one of them
    used to say so. Rendering here rather than in either command is what keeps
    them from drifting apart again.

    Does not list the detected clients: the picker directly below is that list,
    and naming them twice pushed the question off the screen.
    """
    console.print()
    console.print(
        text.Text.assemble(
            ("Set up Opik MCP for your AI client? ", "bold"),
            ("(Recommended)", "green bold"),
        )
    )
    console.print(
        text.Text(
            "Enables your AI assistant to inspect traces, scan your projects\n"
            "for issues, debug experiments, and run Opik commands directly\n"
            "from chat.",
            style="dim",
        )
    )


def render_skill_pack_intro() -> None:
    """The skill pack's case, laid out exactly like :func:`render_mcp_intro`.

    The two are halves of one step. They were written separately and looked it —
    one was three ``rich`` lines and the other was the whole thing crammed into a
    ``click`` label — so the second half read as a different program.
    """
    console.print()
    console.print(
        text.Text.assemble(
            ("Download the Opik skill pack for your AI client? ", "bold"),
            ("(Recommended)", "green bold"),
        )
    )
    console.print(
        text.Text(
            textwrap.fill(consent.SKILL_PACK_PITCH, width=66),
            style="dim",
        )
    )


def render_note(message: str, hint: Optional[str] = None) -> None:
    """A line the user should notice but does not have to act on, plus its fix."""
    console.print(text.Text(message, style="yellow"))
    if hint is not None:
        console.print(text.Text(hint, style="dim"))


class RichInstallView(mcp_view.InstallView):
    def plan(
        self,
        deployment: str,
        transport: str,
        targets: List[mcp_view.PlannedTarget],
        needs_sign_in: bool = False,
    ) -> None:
        # `targets` is deliberately not rendered. It used to head a "Will update"
        # table of each client and the file it would touch, which by then was the
        # third time the same clients were listed — after the consent prompt's
        # "Found:" list and the picker. The results table below reports what was
        # actually written, per client, which is the version worth reading.
        # `LoggingInstallView` still logs the paths for the library path, which
        # has no results table.
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

        # "All" first, and the cursor starts on it. Nothing is pre-ticked — this
        # writes into other tools' config files, so the list stays opt-in — but
        # with an empty selection Enter takes the highlighted row, which meant
        # Enter registered whichever single client happened to be first. Now the
        # row it lands on says All. The numbered-menu fallback above has carried
        # its own "All of the above" all along; this gives the picker the parity.
        chosen = selector.multiselect(
            title=title,
            choices=[selector.Choice(key=_ALL, label="All")]
            + [
                selector.Choice(key=c.key, label=c.label, hint=c.hint)
                for c in candidates
            ]
            + [selector.Choice(key=_SKIP, label="Skip")],
            preselected=preselected,
        )
        if chosen is None:
            return None
        # Skip wins over anything else ticked: it is the row that means "no", and
        # a selection containing both is a user changing their mind, not asking
        # for a partial install. The numbered-menu fallback reads it the same way.
        if _SKIP in chosen:
            return []
        if _ALL in chosen:
            return [c.key for c in candidates]
        return [key for key in chosen if key not in (_ALL, _SKIP)]

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
