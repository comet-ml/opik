"""The ``rich`` rendering of the MCP install, used by ``opik mcp configure``.

Kept in the CLI layer on purpose: ``configurator.mcp.install`` is reachable from
``opik.configure()``, which is a library call and must not take over someone's
stdout. See ``configurator.mcp.view`` for the injection point and the
logger-based default.
"""

import contextlib
import pathlib
import re
import urllib.parse
from typing import Iterator, List, Optional, Tuple

import click
import rich.console
from rich import padding, table, text

from opik.cli import selector
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
    """Style a message so the parts to act on stand out from the prose.

    URLs are coloured and made clickable; commands — in backticks, or on a line
    of their own indented by four spaces — are set in full weight. Everything
    else keeps ``base``, which is usually dim or yellow, so a line that is mostly
    context still leads the eye to the link to open or the thing to type.

    ``rich`` emits the OSC 8 hyperlink only where the terminal advertises
    support, keeps the colour where it does not, and drops every escape when
    stdout is not a terminal at all — so a pipe or a CI log still gets the text
    unchanged, backticks and URLs included, and still copy-pasteable.

    Styles are applied over ranges rather than by splitting the string, so the
    surrounding text keeps ``base`` and the message stays one paragraph.
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
    """A line the configurator says between its questions.

    Indented like the questions it sits among, so a hint reads as belonging to
    the prompt under it rather than as a stray log line in the margin.
    """
    console.print(padding.Padding(_emphasize(message, base="dim"), (0, 0, 0, 2)))


def render_configured(
    configured: opik_configure.Configured, project_url: str, project_exists: bool
) -> None:
    """How `opik configure` closes: what was set up, and where, at a glance.

    A block rather than the sentence `opik.configure()` logs, which ran the
    file, the project and a docs link together into one long grey line — the
    part of the run people most need to find again later, and the easiest to
    skim past.
    """
    console.print()
    headline = (
        "Opik is configured" if configured.saved else "Opik is already configured"
    )
    console.print(text.Text.assemble(("✓ ", "green bold"), (headline, "bold")))

    grid = table.Table.grid(padding=(0, 2))
    grid.add_column(style=_KEY_STYLE, no_wrap=True)
    grid.add_column(overflow="fold")
    grid.add_row("Config file", _collapse_home(configured.config_file))
    if configured.url is not None:
        grid.add_row("Opik", _emphasize(_without_credentials(configured.url)))
    grid.add_row("Workspace", configured.workspace)
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
    """``url`` with any ``user:password@`` removed, for putting on screen.

    An Opik URL can carry basic-auth credentials, and the summary both prints
    it and makes it a link — so the password would be on screen and in the
    link target.
    """
    parsed = urllib.parse.urlsplit(url)
    if parsed.username is None and parsed.password is None:
        return url
    host = parsed.hostname or ""
    if parsed.port is not None:
        host = f"{host}:{parsed.port}"
    return urllib.parse.urlunsplit(parsed._replace(netloc=host))


def confirm_default_yes(question: str) -> bool:
    """A yes/no question that Enter answers yes.

    Every question in the onboarding flow defaults to yes, so they all come
    through here and are indented and worded the same way. ``click``'s
    capitalised ``[Y/n]`` is left to say which answer Enter gives.
    """
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


#: The orange the mark itself is drawn in - the first stop of the gradient in
#: `opik-logo.svg`, which fades from it to crimson.
#:
#: Deliberately NOT `--primary` from the frontend's main.scss. That is indigo,
#: and it is what the product UI accents *with* - buttons, links, focus rings -
#: rather than what Opik looks *like*. This banner is a drawing of the logo, so
#: it takes the logo's colour.
#:
#: Spelled as hex rather than a named ANSI colour, because `yellow` or `red` is
#: whatever the user's terminal theme decided it is, and this is the one piece of
#: branding the CLI shows. Rich degrades it to the nearest available colour where
#: truecolor is missing. The status colours elsewhere in this module stay as they
#: are: green, yellow and red mean something, and are not ours to restyle.
OPIK_ORANGE = "#FB9341"


#: Drawn rather than written: this is the first thing either configure command
#: puts on screen, and a command that is about to edit a tool's configuration
#: should look like it knows what it is.
_BANNER = r"""
   ___        _ _
  / _ \ _ __ (_) | __
 | | | | '_ \| | |/ /
 | |_| | |_) | |   <
  \___/| .__/|_|_|\_\
       |_|
"""


def _render_banner(headline: str, detail: str) -> None:
    """The mark, then what the command about to run is for.

    Shared so the two entry points into onboarding open in the same hand. They
    are one flow seen from different ends — `opik configure` can run
    `opik mcp configure` — and looking like two programs is what made that
    surprising rather than continuous.
    """
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
    """How `opik mcp configure` opens.

    Not :func:`render_mcp_intro`, which asks whether to set MCP up: by the time
    this renders, the user has typed the command that does it. What is left to
    say is what the command is for, so the first screen states it rather than
    putting a question mark after a decision already made.
    """
    _render_banner(
        "Connect your AI client to Opik.",
        "It can then read your traces, find the failing ones, score them,\n"
        "and instrument your code — from chat.",
    )


def render_mcp_intro() -> None:
    """What the MCP step is, before ``opik configure`` asks about it.

    States what the thing is; the ``click`` prompt underneath asks about it. Both
    used to be questions, so the same one arrived twice in a row in slightly
    different words, and the second read as the first not having registered.

    Does not list the detected clients: the picker directly below is that list,
    and naming them twice pushed the question off the screen.
    """
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
    """The question the run would open the agent on, above the offer to do it.

    Only the prompt: the ``click`` prompt underneath is where "Continue in X" is
    asked, and saying it here as well put the same question on screen twice.

    Printed at all because saying yes sends it — so this is the user's one
    chance to read what they are agreeing to ask.
    """
    _render_suggested_prompt(prompt)


def _render_suggested_prompt(prompt: str) -> None:
    """The prompt the run ends on, set so that it is actually read.

    Full weight under an orange heading rather than grey: it is the one thing on
    screen the user either agrees to send or has to paste themselves, and dim
    text is what people skip. No box around it, because the paste path copies it
    straight out of the terminal and a border would come along.
    """
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


def render_handoff_declined(client_display_name: str) -> None:
    """What to do later, for a run that turned the offer down.

    The restart matters and nothing else says so any more: a client that was
    running while its configuration was rewritten has not read it yet.
    """
    console.print()
    console.print(
        text.Text.assemble(
            ("Restart ", "bold"),
            (client_display_name, "bold cyan"),
            (", then paste the prompt above.", ""),
        )
    )


def render_prompt_to_paste(client_display_name: str, prompt: str) -> None:
    """The same ending for a client this command cannot start.

    A GUI app cannot be launched from here, so the question it should open with
    is printed instead. Worth printing rather than dropping: the prompt is the
    part that turns a configured server into something the user has seen work.
    """
    _render_suggested_prompt(prompt)
    console.print(
        text.Text.assemble(
            ("Restart ", "bold"),
            (client_display_name, "bold cyan"),
            (", then paste the prompt above into a new chat.", ""),
        )
    )


def render_restart_note(mcp_installed: bool) -> None:
    """The closing instruction for a run with no one client to name.

    Every ending says what to do next exactly once. This is the one for the
    endings with nothing more specific to say — several clients written at once,
    or a scripted run with no terminal to hand over from.

    ``mcp_installed`` because ``opik configure --install-skills --no-install-mcp``
    reaches this too, and telling that run to ask its client about a server it
    never registered would send the user looking for tools that are not there.
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


class RichInstallView(mcp_view.InstallView):
    def plan(self, deployment: str, transport: str, needs_sign_in: bool) -> None:
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
                    _emphasize(_collapse_home(result.detail), base="yellow"),
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
                _emphasize(detail, base="yellow"),
            )
        console.print(padding.Padding(row, (0, 0, 0, 2), expand=False))

    def done(self) -> None:
        """Close the run. The results rows above already list what was set up."""
        console.print()
        if self._sign_in_failed:
            # The run's last word, after the skill pack: the one step left for
            # the user, and why "done" would not be true yet.
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
            console.print()
            return
        console.print(
            text.Text.assemble(("✓ ", "green bold"), ("Done", "bold")),
        )
        # The one thing here the user may still have to act on, so it should not
        # sit between them and the prompt to try.
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

        # One client, not a set of them. The flow this belongs to ends by handing
        # the chosen client a prompt and starting it, which only means anything
        # for a single client — and registering into several config files at once
        # was never what most runs wanted. `--ai-client` is still repeatable for
        # scripted runs, which skip this picker entirely.
        #
        # The clients come first and the manual row last: it is the way out for
        # someone whose client detection missed, not one of the things being
        # chosen between. A one-item list still gets the picker, because skipping
        # it would skip that row too.
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
        # Escape cancels the run rather than declining the server: there is no
        # "skip" row, so the only way out of the list is to stop. The manual row
        # is the other kind of no — the detection missed their client — and it is
        # worth its place because the answer to it is a link rather than nothing.
        if chosen is None:
            return None
        return [chosen]

    def note(self, message: str) -> None:
        console.print(padding.Padding(_emphasize(message, base="dim"), (0, 0, 0, 2)))


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
