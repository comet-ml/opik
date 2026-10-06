"""How the MCP install narrates itself.

``install`` makes every decision and reports through an :class:`InstallView`;
the CLI supplies the ``rich`` one, and tests inject a recording double.
"""

import abc
import contextlib
import dataclasses
from typing import List, Optional, Sequence, Tuple

from opik.configurator.mcp import targets as mcp_targets


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


#: For a client that prompts for the sign-in on first use, rather than during setup.
SIGN_IN_HINT = (
    "Signing in to Opik happens in your browser. Your AI client will prompt you "
    "the first time it uses Opik, or you can authorize the opik-mcp server from "
    "its MCP settings."
)


def sign_in_failed_message(client_display_name: str) -> str:
    """What to do about a client that was registered but not signed in."""
    target = next(
        (
            target
            for target in mcp_targets.HOST_TARGETS
            if target.display_name == client_display_name
        ),
        None,
    )
    finish = (
        f"Run `{target.sign_in_command}` to finish it"
        if target is not None and target.sign_in_command
        else "Sign in from its MCP settings"
    )
    return (
        f"{client_display_name} is registered but not signed in. {finish} — until "
        "then the server contributes no tools."
    )


def next_steps(
    registered_clients: Sequence[str],
    hosted: bool,
    sign_in_pending: Sequence[str] = (),
    signed_in: Sequence[str] = (),
) -> List[str]:
    """What is left in each client after a run without a terminal, a line each.

    Read by whoever ran it, often a coding agent. ``signed_in`` and
    ``sign_in_pending`` are the clients this run signed in, and those whose
    sign-in it started but that did not finish.
    """
    steps = []
    for key in registered_clients:
        target = mcp_targets.find_target(key)
        if target is None:
            continue
        name = target.display_name
        check = f"check with `{target.status_command}`"
        sign_in = (
            "the sign-in did not finish. Sign in"
            if key in sign_in_pending
            else "sign in"
        )
        if not hosted:
            if target.status_command:
                steps.append(f"{name}: {check}.")
        elif key in signed_in:
            steps.append(f"{name}: signed in; {check}.")
        elif key == "claude-code":
            # `claude mcp login` refuses to run without a terminal, so an agent
            # cannot run it: this one is for the user.
            steps.append(
                f"{name}: {sign_in} from a terminal with "
                f"`{target.sign_in_command}`, or with `/mcp` in a Claude Code "
                f"session; then {check}."
            )
        elif target.sign_in_command:
            steps.append(
                f"{name}: {sign_in} with `{target.sign_in_command}`, then {check}."
            )
        else:
            steps.append(f"{name}: sign in from its MCP settings when it asks.")
    return steps


class InstallView(abc.ABC):
    """Narration hooks for the MCP install flow."""

    #: Whether :meth:`done` should explain the sign-in; carried on the view because
    #: the caller that ends the run never sees the server spec.
    _needs_sign_in: bool = False

    #: Clients registered without a working sign-in, which :meth:`done` reports.
    _sign_in_failed: Tuple[str, ...] = ()

    def sign_in_failed(self, client_display_names: List[str]) -> None:
        """Record clients that could not be signed in, for the run's ending."""
        self._sign_in_failed = tuple(client_display_names)

    def sign_in_handled(self) -> None:
        """Drop the closing sign-in hint: this run already signed in, or failed to."""
        self._needs_sign_in = False

    @abc.abstractmethod
    def plan(self, deployment: str, transport: str, needs_sign_in: bool) -> None:
        """Announce what is about to happen, before anything is written."""

    @abc.abstractmethod
    def step(self, description: str) -> "contextlib.AbstractContextManager[None]":
        """Wrap a slow step (a probe, a download, a verification)."""

    @abc.abstractmethod
    def sign_in(self, client_display_name: str, command: List[str]) -> Optional[int]:
        """Run a client's interactive sign-in; its exit status, or None if it never ran."""

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

        Returns the chosen keys, ``[]`` for a deliberate "none", or ``None`` on
        cancel. A list because the manual row answers with its own key.
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
    """The fallback where the terminal cannot host the picker: type a number.

    One client, like the picker. Ctrl-C answers ``None``, a cancel, as it does
    at the picker.
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
