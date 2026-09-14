"""Whether Opik may set itself up inside another tool, and what to ask.

`opik configure` writes ``~/.opik.config``, which is Opik's own file. Registering
the MCP server and installing the skill pack write into files owned by Cursor,
Claude Code, VS Code and friends. That is a different kind of permission, and this
module is the single place that decides whether we have it.

Both steps ask the same shape of question, so they share one resolver. Everything
here is a pure function of its arguments — no config object, no terminal, no I/O —
so the whole policy is the table in :func:`resolve` and can be read in one sitting.
Rendering and prompting belong to the caller; this only says what to do and why.
"""

import enum
from typing import Callable, List, NamedTuple, Optional


class Decision(enum.Enum):
    """What to do about a step the user may or may not have opted into."""

    SKIP = "skip"
    PROCEED = "proceed"
    ASK = "ask"


class Reason(enum.Enum):
    """Why :func:`resolve` decided what it did.

    Carried so the caller can explain itself without re-deriving the inputs.
    Every "we skipped your editor" message the CLI prints used to guess at this,
    and guessed wrong for the most common unattended case.
    """

    DECLINED = "declined"
    REQUESTED = "requested"
    NO_TERMINAL = "no_terminal"
    ASSUME_YES = "assume_yes"
    NOTHING_DETECTED = "nothing_detected"
    ASKING = "asking"


class Verdict(NamedTuple):
    decision: Decision
    reason: Reason


def resolve(
    flag: Optional[bool],
    *,
    assume_yes: bool,
    interactive: bool,
    anything_detected: bool,
) -> Verdict:
    """Decide one setup step from the flag that named it and the situation.

    ``flag`` is the tri-state of ``--install-mcp`` / ``--install-skills`` /
    ``--skills``: ``True`` asked for it, ``False`` refused it, ``None`` never said.

    The order of these rules is the policy, so it is worth stating why each one
    sits where it does:

    1. An explicit refusal wins over everything, including a later ``-y``.
    2. An explicit request proceeds *without needing a terminal*. This is the case
       the whole non-interactive path exists for: a coding agent asked to set Opik
       up has no tty, but the user asked for this seconds ago in chat. Checking the
       terminal first would break exactly the caller we want to support.
    3. No terminal and no flag: skip. Nobody said to do this and there is nobody
       to ask. This is checked *before* ``assume_yes`` so an unattended run reports
       the honest reason rather than blaming a flag it never passed.
    4. ``-y`` alone: skip. It means "stop asking me questions about Opik", not
       "edit my editor's configuration".
    5. Nothing detected: skip, because there is nothing worth asking about. Only
       reached when we would otherwise ask — a named flag has already proceeded by
       then, and the installer is what reports having found no client, since it is
       the part that knows.
    """
    if flag is False:
        return Verdict(Decision.SKIP, Reason.DECLINED)
    if flag is True:
        return Verdict(Decision.PROCEED, Reason.REQUESTED)
    if not interactive:
        return Verdict(Decision.SKIP, Reason.NO_TERMINAL)
    if assume_yes:
        return Verdict(Decision.SKIP, Reason.ASSUME_YES)
    if not anything_detected:
        return Verdict(Decision.SKIP, Reason.NOTHING_DETECTED)
    return Verdict(Decision.ASK, Reason.ASKING)


def granted(verdict: Verdict, ask: Callable[[], bool]) -> bool:
    """Turn a verdict into a yes or no, asking only when that is the verdict.

    ``ask`` is injected because the two surfaces ask differently — the CLI through
    rich, ``opik.configure()`` through plain text — and it carries its own wording
    rather than being handed a prompt string. Passing the text through here meant
    passing one the CLI asker then ignored.
    """
    if verdict.decision is Decision.ASK:
        return ask()
    return verdict.decision is Decision.PROCEED


def readable_list(names: List[str]) -> str:
    """``a``, ``a and b``, ``a, b and c`` — a list a person would read aloud."""
    if len(names) <= 1:
        # Empty joins to "", which reads correctly in a sentence that a caller
        # only builds when something was found.
        return "".join(names)
    return f"{', '.join(names[:-1])} and {names[-1]}"


def mcp_prompt(detected: List[str]) -> str:
    """The consent prompt, framed so it does not read as one more log line.

    Plain text with blank lines and an indent rather than anything richer: this
    runs from ``opik.configure()`` too, which must not take over the caller's
    stdout with a rendered panel.
    """
    return (
        "\n"
        "  ─── AI clients ───────────────────────────────────────────\n"
        "\n"
        f"  Found {readable_list(detected)}.\n"
        "\n"
        "  The Opik MCP server lets them read traces, log scores and run\n"
        "  experiments from chat.\n"
        "\n"
        "  Register it with them? (y/N) "
    )


SKILL_PACK_PITCH: str = (
    "It teaches your AI client how to instrument code with Opik, wire up "
    "integrations, and run test suites."
)
"""The case for the pack, as one line — used by the CLI, which wraps it itself.

Says the same thing as the body of :data:`SKILLS_PROMPT` below, which is hand-
wrapped for plain-text output. Keep the two in step; "AI client" is the term the
rest of the CLI uses for these tools."""


SKILLS_PROMPT: str = (
    "\n  Recommended: also install the Opik skill pack?\n"
    "  It teaches your AI client how to instrument code with Opik, wire\n"
    "  up integrations, and run test suites. (Y/n) "
)
"""Asked after the server step, so the user answers with its output in front of
them, and recommended — hence the default yes. The clients are not named again
because the server step just listed them."""
