"""How the MCP install narrates itself.

``install`` makes every decision and reports through an :class:`InstallView`;
the CLI supplies the ``rich`` one, and tests inject a recording double.
"""

import abc
import contextlib
import dataclasses
from typing import List, Optional, Tuple

from opik.configurator.mcp import spec as mcp_spec


@dataclasses.dataclass
class HostChoice:
    """One selectable AI host, for the target-selection prompt."""

    key: str
    label: str
    hint: str = ""


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


#: The picker row for "none of these is my client". Returned in place of host
#: keys, so the installer answers it with the manual-setup instructions rather
#: than treating it as a silent decline. Not a host key and cannot collide with
#: one: `mcp_targets.HOST_KEYS` are plain names like `claude-code`.
MANUAL_SETUP = "__manual__"

#: Label for that row, shared so the rich picker and the numbered menu agree.
MANUAL_SETUP_LABEL = "My AI client is not listed"


#: How the sign-in step is phrased, once, so both views agree.
#:
#: Only reaches a user whose run attempted no sign-in at all — a client that
#: takes the config and prompts on first use, rather than one like Codex or
#: Claude Code that opens the browser during setup. That is what lets it say
#: plainly what will happen; it used to hedge with "may have opened it during
#: setup" because it also printed after a login that had already succeeded.
SIGN_IN_HINT = (
    "Signing in to Opik happens in your browser. Your AI client will prompt you "
    "the first time it uses Opik, or you can authorize the opik-mcp server from "
    "its MCP settings."
)


def sign_in_failed_message(client_display_name: str) -> str:
    """What to do about a client that was registered but not signed in."""
    return (
        f"{client_display_name} is registered but not signed in. Run "
        f"`claude mcp login {mcp_spec.SERVER_NAME}` to finish it — until then the server "
        "contributes no tools."
    )


class InstallView(abc.ABC):
    """Narration hooks for the MCP install flow."""

    #: Whether :meth:`done` should still explain the sign-in. Set from the
    #: transport in :meth:`plan` and cleared by :meth:`sign_in_handled` once the
    #: run knows better. Kept here rather than passed to :meth:`done` because the
    #: CLI closes the run from ``cli.assistants``, which never sees the server
    #: spec — the view carries the fact across that gap. A class attribute, so a
    #: view that is never planned still renders.
    _needs_sign_in: bool = False

    #: Clients registered without a working sign-in. Carried to :meth:`done`,
    #: which must not call the run done while one of them has no tools yet.
    _sign_in_failed: Tuple[str, ...] = ()

    def sign_in_failed(self, client_display_names: List[str]) -> None:
        """Record clients that were registered but could not be signed in.

        Recorded rather than printed where it happens: the run goes on to verify
        the server and install the skill pack, and the one thing left for the
        user to do has to be what the run ends on.
        """
        self._sign_in_failed = tuple(client_display_names)

    def sign_in_handled(self) -> None:
        """Drop the closing hint: this run has already said what applies.

        Two ways to get here, and the hint is wrong in both. A sign-in that
        succeeded is done — the browser came and went during setup, so a closing
        note about a prompt to expect describes the past. One that failed has
        already been answered by a note naming the exact command to run, which
        the general version would only repeat more vaguely.
        """
        self._needs_sign_in = False

    @abc.abstractmethod
    def plan(self, deployment: str, transport: str, needs_sign_in: bool) -> None:
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
    def done(self) -> None:
        """Close the run, with whatever is still left for the user to do."""

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
        self, title: str, candidates: List[HostChoice]
    ) -> Optional[List[str]]:
        """Ask which host to install for.

        Returns the chosen keys, or ``None`` if the user cancelled — distinct
        from an empty list, which means "none of them, deliberately". Still a
        list because the manual row answers with its own key rather than a host.
        """


def _single_candidate_menu(candidate: HostChoice) -> List[str]:
    """One detected client: a yes/no that also has the manual door in it.

    Still answers to Y, N and a bare Enter, because that is what this prompt has
    always accepted and what anything piping input into it sends. The numbers
    are the addition. Without them "my AI client is not listed" existed only
    once two clients were detected, so the user this most concerns — one client
    found, and it is not theirs — was the one who could not reach it.
    """
    prompt = "\n".join(
        [
            f"Detected {candidate.label}. Install the Opik MCP server for it?",
            "  1 - Yes",
            f"  2 - {MANUAL_SETUP_LABEL}",
            "  3 - Skip",
            "\nY/n, or a number\n> ",
        ]
    )

    while True:
        answer = input(prompt).strip().upper()
        if answer in ("Y", "YES", "1", ""):
            return [candidate.key]
        if answer in ("N", "NO", "3"):
            return []
        if answer == "2":
            return [MANUAL_SETUP]
        print("  Please enter one of the numbers above.")


def numbered_menu(title: str, candidates: List[HostChoice]) -> Optional[List[str]]:
    """The portable fallback: type a number.

    Used by the rich view where the terminal cannot host its picker. A single
    candidate keeps its own shape; see :func:`_single_candidate_menu`.

    One client, like the picker this stands in for. It used to offer "All of the
    above" and accept ``1,2`` — which registered servers this flow then could not
    finish for, since it ends by starting the one client that was chosen.

    Ctrl-C answers ``None``, as it does at the picker: a cancel rather than an
    abort, so the flow can still report the run and stop cleanly.
    """
    try:
        if len(candidates) == 1:
            return _single_candidate_menu(candidates[0])
        return _several_candidates_menu(title, candidates)
    except KeyboardInterrupt:
        return None


def _several_candidates_menu(title: str, candidates: List[HostChoice]) -> List[str]:
    host_count = len(candidates)
    manual_choice = host_count + 1
    skip_choice = host_count + 2

    lines = [title]
    for index, candidate in enumerate(candidates, start=1):
        lines.append(f"  {index} - {candidate.label}")
    lines.append(f"  {manual_choice} - {MANUAL_SETUP_LABEL}")
    lines.append(f"  {skip_choice} - Skip")
    lines.append("\nEnter a number\n> ")
    prompt = "\n".join(lines)

    while True:
        answer = input(prompt).strip()

        if answer.isdigit():
            number = int(answer)
            if number == skip_choice:
                return []
            if number == manual_choice:
                return [MANUAL_SETUP]
            if 1 <= number <= host_count:
                return [candidates[number - 1].key]

        print("  Please enter one of the numbers above.")
