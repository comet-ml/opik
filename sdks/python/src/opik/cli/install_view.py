"""The ``rich`` rendering of the onboarding flows, used by both configure commands.

Kept in the CLI layer: ``configurator`` is reachable from ``opik.configure()``,
a library call that must not take over someone's stdout.
"""

import contextlib
import pathlib
import re
import urllib.parse
from typing import Iterator, List, Optional, Tuple

import click
import rich.console
from rich import control, padding, table, text

from opik.cli import selector
from opik.cli import terminal_session
from opik.configurator import configure as opik_configure
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


#: Bare URLs, stopping before the punctuation that usually follows one in prose.
_URL = re.compile(r"https?://[^\s)\]}>,;\"']+")

#: Punctuation that ends a sentence rather than an address.
_SENTENCE_END = ".,;:!?"


#: A command or name in backticks, the way messages here quote what to type.
_CODE_SPAN = re.compile(r"`[^`\n]+`")

#: A line indented by four spaces, which messages here use for a command or a
#: config snippet to copy.
_CODE_LINE = re.compile(r"^ {4}\S.*$", re.MULTILINE)

#: What a command looks like against the text around it: full weight, in the
#: terminal's own colour, so it stands out from both the dim and the yellow.
_CODE_STYLE = "bold not dim default"


def _emphasize(message: str, base: str = "") -> text.Text:
    """Style a message so the links to open and the commands to type stand out.

    URLs become clickable; commands in backticks or on a four-space-indented line
    are set in full weight; the rest keeps ``base``. ``rich`` drops every escape
    when stdout is not a terminal, so piped output keeps the plain text.
    """
    rendered = text.Text(message, style=base)
    for pattern in (_CODE_LINE, _CODE_SPAN):
        for match in pattern.finditer(message):
            start = match.start() + len(match.group()) - len(match.group().lstrip())
            rendered.stylize(_CODE_STYLE, start, match.end())
    for match in _URL.finditer(message):
        # Trailing sentence punctuation is not part of the address. It cannot be
        # excluded by the pattern, because a URL is full of dots — so the match
        # runs long and the tail is trimmed back here.
        end = match.end()
        while end > match.start() and message[end - 1] in _SENTENCE_END:
            end -= 1
        rendered.stylize(
            # `not dim`: a link inside a grey hint is the part to click, and
            # without it the link inherits the grey along with the rest.
            f"bold not dim cyan underline link {message[match.start() : end]}",
            match.start(),
            end,
        )
    return rendered


def render_hint(message: str) -> None:
    """A line pointing somewhere — typically where to get something."""
    console.print(_emphasize(message, base="dim"))


def render_configure_hint(message: str) -> None:
    """A configurator hint, indented with the questions it sits among."""
    console.print(padding.Padding(_emphasize(message, base="dim"), (0, 0, 0, 2)))


def render_configured(
    configured: opik_configure.Configured, project_url: str, project_exists: bool
) -> None:
    """How `opik configure` closes: what was set up, and where to open it."""
    console.print()
    headline = (
        "Opik is configured" if configured.saved else "Opik is already configured"
    )
    console.print(text.Text.assemble(("✓ ", "green bold"), (headline, "bold")))

    grid = table.Table.grid(padding=(0, 2))
    grid.add_column(style=_KEY_STYLE, no_wrap=True)
    grid.add_column(overflow="fold")
    # Text, not str: a table cell reads `[...]` as markup, and a path or a
    # workspace name is the user's own text.
    grid.add_row("Config file", text.Text(_collapse_home(configured.config_file)))
    if configured.url is not None:
        grid.add_row("Opik", _emphasize(_without_credentials(configured.url)))
    grid.add_row("Workspace", text.Text(configured.workspace))
    grid.add_row("Project", text.Text(configured.project_name, style="bold"))
    # Shown in full rather than behind the project name: not every terminal
    # makes a hyperlink clickable, and a visible URL can still be copied.
    open_row = _emphasize(_without_credentials(project_url))
    if not project_exists:
        open_row.append(
            "\nThe project appears here after its first trace.", style="dim"
        )
    grid.add_row("Open", open_row)
    console.print(padding.Padding(grid, _FIELDS_INDENT, expand=False))
    console.print(
        padding.Padding(
            _emphasize(
                f"To log to another project: {opik_configure.PROJECT_NAME_DOCS_URL}",
                base="dim",
            ),
            _FIELDS_INDENT,
        )
    )


def _without_credentials(url: str) -> str:
    """``url`` without any ``user:password@``, so a password is never shown or linked."""
    parsed = urllib.parse.urlsplit(url)
    if parsed.username is None and parsed.password is None:
        return url
    host = parsed.hostname or ""
    if parsed.port is not None:
        host = f"{host}:{parsed.port}"
    return urllib.parse.urlunsplit(parsed._replace(netloc=host))


def confirm_default_yes(question: str) -> bool:
    """A yes/no question that Enter answers yes, indented like every other prompt."""
    return click.confirm(f"  {question}", default=True)


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


#: The logo's own orange (the first stop of `opik-logo.svg`), as hex so it does not
#: depend on the terminal theme.
OPIK_ORANGE = "#FB9341"


_BANNER = r"""
   ___        _ _
  / _ \ _ __ (_) | __
 | | | | '_ \| | |/ /
 | |_| | |_) | |   <
  \___/| .__/|_|_|\_\
       |_|
"""


def _render_banner(headline: str, detail: str) -> None:
    """The logo, then what the command is for — shared by both configure commands."""
    console.print(text.Text(_BANNER, style=f"bold {OPIK_ORANGE}"))
    console.print(
        padding.Padding(
            text.Text.assemble((f"{headline}\n", "bold"), (detail, "dim")),
            (0, 0, 1, 2),
        )
    )


def render_configure_banner() -> None:
    """How `opik configure` opens."""
    _render_banner(
        "Set up Opik on this machine.",
        "A few questions, then your code and your AI client can both\n"
        "reach your workspace.",
    )


def render_mcp_banner() -> None:
    """How `opik mcp configure` opens. States the purpose rather than asking:
    running the command was the answer."""
    _render_banner(
        "Connect your AI client to Opik.",
        "It can then read your traces, find the failing ones, score them,\n"
        "and instrument your code — from chat.",
    )


def render_connection(opik_url: str, workspace: Optional[str], source: str) -> None:
    """Which saved Opik the AI client is being connected to, from where, and how
    to choose another.

    Said up front because nothing later names it, and a wrong Opik is otherwise
    only found out once the AI client cannot reach it. Laid out like the block
    `opik configure` closes on, which says the same things about the same file.
    """
    console.print(text.Text("Connecting to", style="bold"))
    grid = table.Table.grid(padding=(0, 2))
    grid.add_column(style=_KEY_STYLE, no_wrap=True)
    grid.add_column(overflow="fold")
    grid.add_row("Opik", _emphasize(_without_credentials(opik_url.rstrip("/"))))
    # Text, not str: a table cell reads `[...]` as markup.
    if workspace:
        grid.add_row("Workspace", text.Text(workspace))
    grid.add_row("From", text.Text(_collapse_home(source)))
    console.print(padding.Padding(grid, _FIELDS_INDENT, expand=False))
    console.print(
        padding.Padding(
            text.Text.assemble(
                ("To change the MCP connection config: ", "dim"),
                ("opik mcp configure --ignore-opik-config", _CODE_STYLE),
            ),
            _FIELDS_INDENT,
        )
    )
    console.print()


def render_mcp_intro() -> None:
    """What MCP is, above `opik configure`'s question about it. The picker below
    lists the clients, so this does not."""
    console.print()
    console.print(
        text.Text.assemble(
            ("Opik MCP ", "bold"),
            ("(Recommended)", "green bold"),
        )
    )
    # Indented with the question under it, so the pitch and the prompt read as
    # one block under the heading rather than as two unrelated lines.
    console.print(
        padding.Padding(
            text.Text(
                "Lets your AI client inspect traces, scan your projects for\n"
                "issues, debug experiments, and run Opik commands directly from\n"
                "chat.",
                style="dim",
            ),
            (0, 0, 0, 2),
        )
    )


def render_handoff_offer(prompt: str) -> None:
    """The prompt that saying yes will send, above the question that asks."""
    _render_suggested_prompt(prompt)


def _render_suggested_prompt(prompt: str) -> None:
    """The closing prompt, in full weight rather than grey so it is read. No box:
    the paste path copies it straight out of the terminal."""
    console.print()
    console.print(text.Text("Suggested first prompt", style=f"bold {OPIK_ORANGE}"))
    console.print(padding.Padding(text.Text(prompt, style="bold"), (0, 0, 1, 2)))


def render_handoff(client_display_name: str) -> None:
    """The last thing shown before the agent takes the terminal."""
    console.print()
    console.print(
        text.Text.assemble(
            ("Starting ", "bold"),
            (client_display_name, "bold cyan"),
            ("…", "bold"),
        )
    )


def render_handoff_declined(client_display_name: str, replace_offer: bool) -> None:
    """The ending for a run that turned the offer down.

    ``replace_offer`` after a Ctrl-C at the offer, which leaves its line ending in
    an echoed ``^C``: the ending is written over that line instead of under it.
    """
    if replace_offer and console.is_terminal:
        console.control(
            control.Control(
                control.ControlType.CARRIAGE_RETURN,
                (control.ControlType.ERASE_IN_LINE, 2),
            )
        )
    else:
        console.print()
    console.print(
        text.Text.assemble(
            ("Restart ", "bold"),
            (client_display_name, "bold cyan"),
            (", then paste the prompt above.", ""),
        )
    )


def render_prompt_to_paste(client_display_name: str, prompt: str) -> None:
    """The ending for a client that cannot be started from here."""
    _render_suggested_prompt(prompt)
    console.print(
        text.Text.assemble(
            ("Restart ", "bold"),
            (client_display_name, "bold cyan"),
            (", then paste the prompt above into a new chat.", ""),
        )
    )


def render_restart_note(mcp_installed: bool) -> None:
    """The ending for a run with no one client to name.

    ``mcp_installed`` is False for `--install-skills --no-install-mcp`, which
    must not be told to ask about a server it never registered.
    """
    console.print()
    if not mcp_installed:
        console.print(
            text.Text.assemble(
                ("Restart your AI client", "bold"),
                (" to pick up the Opik skill pack.", ""),
            )
        )
        return

    console.print(
        text.Text.assemble(
            ("Restart your AI client", "bold"),
            (", then ask it to ", ""),
            ('"list my Opik projects via Opik MCP"', "green"),
            (".", ""),
        )
    )


def render_note(message: str, hint: Optional[str] = None) -> None:
    """A line the user should notice but does not have to act on, plus its fix."""
    console.print(_emphasize(message, base="yellow"))
    if hint is not None:
        console.print(_emphasize(hint, base="dim"))


#: Wide enough for every status label, so rows printed apart still line up.
_STATUS_LABEL_WIDTH = 9


def _status_row(mark: str, mark_style: str, label: str, detail: text.Text) -> None:
    grid = table.Table.grid(padding=(0, 2))
    grid.add_column(no_wrap=True)
    grid.add_column(style=_KEY_STYLE, no_wrap=True, min_width=_STATUS_LABEL_WIDTH)
    grid.add_column(overflow="fold")
    grid.add_row(text.Text(mark, style=mark_style), label, detail)
    console.print(padding.Padding(grid, (0, 0, 0, 2), expand=False))


class RichInstallView(mcp_view.InstallView):
    #: Clients the server was added to, named in the row that says it works.
    _added: Tuple[str, ...] = ()
    #: Whether the blank line above the status rows is already on screen.
    _rows_started: bool = False

    def plan(self, deployment: str, transport: str, needs_sign_in: bool) -> None:
        # Nothing shown: the command already says it is setting up MCP, and the
        # sign-in walks the user through itself.
        self._needs_sign_in = needs_sign_in

    @contextlib.contextmanager
    def step(self, description: str) -> Iterator[None]:
        # `console.status` degrades to a single printed line when stdout is not a
        # terminal, so this is safe in CI and when piped to a file.
        with console.status(f"[dim]{description}...[/dim]", spinner="dots"):
            yield

    def sign_in(self, client_display_name: str, command: List[str]) -> Optional[int]:
        console.print()
        returncode = terminal_session.run(
            command,
            header=f"Starting authentication for Opik MCP in {client_display_name}…",
            hide_first_line="Starting authentication for",
            # A new account's sign-up does not come back to this authorization,
            # but the client is still waiting on it.
            hint_after=(
                "Waiting for authorization",
                "New to Opik? Once your account is created, open the link above "
                "again to finish.",
            ),
        )
        # A sign-in that worked is erased, leaving the blank line above it for
        # the rows that follow.
        self._rows_started = returncode == 0
        return returncode

    def _start_rows(self) -> None:
        if not self._rows_started:
            console.print()
            self._rows_started = True

    def results(self, results: List[mcp_view.TargetResult]) -> None:
        # Successes are reported once the server is verified, as one row; only
        # failures are worth a row of their own here.
        self._added = tuple(r.display_name for r in results if r.succeeded)
        for result in results:
            if not result.succeeded:
                self._start_rows()
                _status_row(
                    "✗",
                    "red",
                    result.display_name,
                    _emphasize(_collapse_home(result.detail), base="yellow"),
                )

    def verification(self, succeeded: bool, detail: str) -> None:
        self._start_rows()
        if not succeeded:
            _status_row(
                "✗",
                "red",
                "Opik MCP",
                _emphasize(
                    f"added to {', '.join(self._added)}, but not working: {detail}",
                    base="yellow",
                ),
            )
            return
        working = [name for name in self._added if name not in self._sign_in_failed]
        if working:
            _status_row(
                "✓",
                "green",
                "Opik MCP",
                text.Text(f"available in {', '.join(working)}"),
            )
        for name in self._sign_in_failed:
            _status_row(
                "!",
                "yellow",
                "Opik MCP",
                text.Text(f"added to {name}, not signed in yet", style="yellow"),
            )

    def skill_pack(self, result: skills_install.InstallResult) -> bool:
        """Report a skill-pack install. Returns whether it succeeded."""
        if not result.succeeded:
            self.problem(f"Could not install the Opik skill pack: {result.error}.")
            return False

        self._start_rows()
        clients = skills_roots.display_names(list(result.linked))
        where = (
            f"available in {', '.join(clients)}"
            if clients
            else f"installed in {_collapse_home(str(result.shared_dir))}"
        )
        _status_row("✓", "green", "Skills", text.Text(where))
        for host_key, message in result.link_errors.items():
            label = ", ".join(skills_roots.display_names([host_key]))
            self.problem(f"{label}: {message}")
        return True

    def done(self) -> None:
        """Close the run with anything the user still has to do, if there is any.

        No "Done": the run goes on to the suggested first prompt.
        """
        if self._sign_in_failed:
            console.print()
            console.print(
                text.Text.assemble(
                    ("! ", "yellow bold"), ("Set up, but not signed in yet", "bold")
                )
            )
            for name in self._sign_in_failed:
                console.print(
                    padding.Padding(
                        _emphasize(
                            mcp_view.sign_in_failed_message(name), base="yellow"
                        ),
                        (0, 0, 0, 2),
                    )
                )
            return
        if self._needs_sign_in:
            console.print()
            console.print(
                padding.Padding(
                    text.Text.assemble(
                        ("Signing in: ", "bold"),
                        (mcp_view.SIGN_IN_HINT, "dim"),
                    ),
                    (0, 0, 0, 2),
                )
            )

    def skipped(self, message: str) -> None:
        console.print()
        console.print(_emphasize(message, base="dim"))
        console.print()

    def problem(self, message: str) -> None:
        console.print()
        console.print(_emphasize(_collapse_home(message), base="yellow"))
        console.print()

    def choose_hosts(
        self,
        title: str,
        candidates: List[mcp_view.HostChoice],
    ) -> Optional[List[str]]:
        # A terminal that cannot host a picker still gets the inherited numbered
        # menu rather than an error.
        if not selector.is_supported():
            return mcp_view.numbered_menu(title, candidates)

        # One client: the flow ends by starting it. The manual row goes last, and
        # is kept for a single client too, as the way out when detection missed.
        chosen = selector.choose_one(
            title=title,
            choices=[
                selector.Choice(key=c.key, label=c.label, hint=c.hint)
                for c in candidates
            ]
            + [
                selector.Choice(
                    key=mcp_view.MANUAL_SETUP,
                    label=mcp_view.MANUAL_SETUP_LABEL,
                    hint="show manual setup",
                )
            ],
        )
        # There is no "skip" row, so Escape is a cancel rather than a decline.
        if chosen is None:
            return None
        return [chosen]

    def note(self, message: str) -> None:
        console.print(padding.Padding(_emphasize(message, base="dim"), (0, 0, 0, 2)))
