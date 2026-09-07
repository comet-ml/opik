"""How the MCP install narrates itself.

The install flow is shared by two callers with different needs. ``opik.configure()``
is a library call that must not paint boxes on someone's stdout, so it narrates
through the logger. ``opik mcp configure`` is a wizard a person is watching, and
it should look like one — the rest of the command group already renders with
``rich`` (see ``cli.status_view``), so the installer looking like raw log output
was the odd one out.

Both are served by injecting a view rather than branching inside the flow:
:class:`LoggingInstallView` is the default and preserves library behaviour;
``cli.mcp`` passes :class:`RichInstallView`. Tests inject a recording double,
which also decouples them from exact log strings.

Presentation only — every decision is made by ``install``.
"""

import abc
import contextlib
import dataclasses
import logging
import pathlib
from typing import Iterator, List, Optional

from opik.configurator import interactive_helpers

LOGGER = logging.getLogger(__name__)


@dataclasses.dataclass
class HostChoice:
    """One selectable AI host, for the target-selection prompt."""

    key: str
    label: str
    hint: str = ""


@dataclasses.dataclass
class PlannedTarget:
    """One AI host the install is about to touch, and where."""

    display_name: str
    location: str


@dataclasses.dataclass
class TargetResult:
    display_name: str
    detail: str
    succeeded: bool
    # Short form for a display that already showed where the file is.
    summary: Optional[str] = None

    @property
    def short(self) -> str:
        return self.summary or self.detail


#: How the sign-in step is phrased, once, so both views agree.
SIGN_IN_HINT = (
    "Depending on your assistant, you will either be prompted with a sign-in "
    "link the first time it uses Opik, or need to authorize the opik-mcp "
    "server yourself from its MCP settings."
)


class InstallView(abc.ABC):
    """Narration hooks for the MCP install flow."""

    #: Whether the connection needs a sign-in, as decided by ``install`` and
    #: handed over in :meth:`plan`. Kept here rather than passed to :meth:`done`
    #: because the CLI closes the run from ``cli.assistants``, which never sees
    #: the server spec — the view carries the fact across that gap. A class
    #: attribute, so a view that is never planned still renders.
    _needs_sign_in: bool = False

    @abc.abstractmethod
    def plan(
        self,
        deployment: str,
        transport: str,
        targets: List[PlannedTarget],
        needs_sign_in: bool = False,
    ) -> None:
        """Announce what is about to happen, before anything is written."""

    @abc.abstractmethod
    def step(self, description: str) -> "contextlib.AbstractContextManager[None]":
        """Wrap a slow step (a probe, a download, a verification)."""

    @abc.abstractmethod
    def results(self, results: List[TargetResult]) -> None:
        """Report what was written, per host."""

    @abc.abstractmethod
    def verification(self, succeeded: bool, detail: str) -> None:
        """Report whether the registration actually works."""

    @abc.abstractmethod
    def done(self, components: List[str], assistants: List[str]) -> None:
        """Close the run: what was set up, for whom, and what is left to do."""

    @abc.abstractmethod
    def skipped(self, message: str) -> None:
        """Nothing was installed, and why."""

    @abc.abstractmethod
    def problem(self, message: str) -> None:
        """A blocking failure, with the fix."""

    @abc.abstractmethod
    def note(self, message: str) -> None:
        """Something worth knowing that does not change the outcome."""

    @abc.abstractmethod
    def choose_hosts(
        self, title: str, candidates: List[HostChoice], preselected: List[str]
    ) -> Optional[List[str]]:
        """Ask which hosts to install for.

        Returns the chosen keys, or ``None`` if the user cancelled — distinct
        from an empty list, which means "none of them, deliberately".
        """


class LoggingInstallView(InstallView):
    """The library-safe default: everything through the logger, no cursor control."""

    def plan(
        self,
        deployment: str,
        transport: str,
        targets: List[PlannedTarget],
        needs_sign_in: bool = False,
    ) -> None:
        self._needs_sign_in = needs_sign_in
        LOGGER.info(
            "Setting up the Opik MCP server (%s, %s) for: %s",
            deployment,
            transport,
            ", ".join(f"{t.display_name} -> {t.location}" for t in targets),
        )

    @contextlib.contextmanager
    def step(self, description: str) -> Iterator[None]:
        LOGGER.info("%s...", description)
        yield

    def results(self, results: List[TargetResult]) -> None:
        for result in results:
            if result.succeeded:
                LOGGER.info("%s: %s", result.display_name, result.detail)
            else:
                LOGGER.warning("%s: %s", result.display_name, result.detail)

    def verification(self, succeeded: bool, detail: str) -> None:
        if succeeded:
            LOGGER.info("Verified: %s.", detail)
        else:
            LOGGER.warning(
                "The Opik MCP server was registered, but verification failed: %s",
                detail,
            )

    def done(self, components: List[str], assistants: List[str]) -> None:
        LOGGER.info(
            "Done. %s set up for %s. Restart %s, then ask it to 'list my Opik "
            "projects via Opik MCP'.",
            " and ".join(components) or "Nothing",
            ", ".join(assistants) or "your AI client",
            "them" if len(assistants) > 1 else "it",
        )
        if self._needs_sign_in:
            LOGGER.info("Signing in: %s", SIGN_IN_HINT)

    def skipped(self, message: str) -> None:
        LOGGER.info(message)

    def problem(self, message: str) -> None:
        LOGGER.warning(message)

    def note(self, message: str) -> None:
        LOGGER.info(message)

    def choose_hosts(
        self, title: str, candidates: List[HostChoice], preselected: List[str]
    ) -> Optional[List[str]]:
        return numbered_menu(title, candidates)


def numbered_menu(title: str, candidates: List[HostChoice]) -> Optional[List[str]]:
    """The portable fallback: type a number.

    A module-level function rather than a base-class method so the rich view can
    fall back to it without inheriting a logging view it otherwise overrides
    entirely. A single candidate is a yes/no rather than a one-item menu.
    """
    if len(candidates) == 1:
        confirmed = interactive_helpers.ask_user_for_approval(
            f"Detected {candidates[0].label}. Install the Opik MCP server "
            f"for it? (Y/n) "
        )
        return [candidates[0].key] if confirmed else []

    host_count = len(candidates)
    all_choice = host_count + 1
    skip_choice = host_count + 2

    lines = [title]
    for index, candidate in enumerate(candidates, start=1):
        lines.append(f"  {index} - {candidate.label}")
    lines.append(f"  {all_choice} - All of the above")
    lines.append(f"  {skip_choice} - Skip")
    lines.append("\nEnter a number, or several separated by commas (e.g. 1,2)\n> ")
    prompt = "\n".join(lines)

    while True:
        raw = [token.strip() for token in input(prompt).split(",") if token.strip()]

        if not raw or not all(token.isdigit() for token in raw):
            LOGGER.error("Wrong choice. Please try again.\n")
            continue

        numbers = [int(token) for token in raw]

        if skip_choice in numbers:
            return []
        if all_choice in numbers:
            return [candidate.key for candidate in candidates]
        if all(1 <= number <= host_count for number in numbers):
            return [candidates[number - 1].key for number in dict.fromkeys(numbers)]

        LOGGER.error("Wrong choice. Please try again.\n")


def display_path(path: pathlib.Path) -> str:
    """Render a path with the user's home directory collapsed to ``~``."""
    home = str(pathlib.Path.home())
    value = str(path)
    return f"~{value[len(home) :]}" if value.startswith(home) else value


_DEFAULT_VIEW: Optional[InstallView] = None


def default_view() -> InstallView:
    global _DEFAULT_VIEW
    if _DEFAULT_VIEW is None:
        _DEFAULT_VIEW = LoggingInstallView()
    return _DEFAULT_VIEW
