"""Consent policy and prompt wording for the assistant setup step.

``configure.py`` owns the decision to *run* this step. The rules for whether to
ask, and the words used when asking, live here — otherwise the general configure
flow accumulates the specifics of every assistant feature bolted onto it, which
is how a 90-line file becomes a 900-line one.

Everything here is a pure function of its arguments: no config object, no
terminal, no I/O. The caller supplies what was detected and whether a terminal is
present, and gets back a decision plus the text to show. That keeps the policy
testable on its own, and keeps this module free of the rendering concerns that
belong to ``cli/``.
"""

import enum
from typing import List, Optional


class Decision(enum.Enum):
    """What to do about a step the user may or may not have opted into."""

    SKIP = "skip"
    PROCEED = "proceed"
    ASK = "ask"


def readable_list(names: List[str]) -> str:
    """``a``, ``a and b``, ``a, b and c`` — a list a person would read aloud."""
    if len(names) <= 1:
        # Empty joins to "", which reads correctly in a sentence that a caller
        # only builds when something was found.
        return "".join(names)
    return f"{', '.join(names[:-1])} and {names[-1]}"


def mcp_decision(
    *,
    install_mcp: Optional[bool],
    automatic_approvals: bool,
    interactive: bool,
    detected: List[str],
) -> Decision:
    """Whether to register the MCP server, ask first, or leave it alone.

    - ``install_mcp is False``: skip.
    - **No terminal: skip, whatever the flags say.** Registering a server edits
      configuration files owned by other tools, and that is not something to do
      to a machine nobody is sitting at — a CI runner, a Docker build, a cron
      job. A flag expresses a preference, not a licence to write outside a
      session the user is present for.
    - ``install_mcp is True`` in a session: proceed without asking.
    - ``automatic_approvals`` (``-y``): skip. A blanket yes-to-everything should
      not reach into another tool's config.
    - Nothing detected: skip. There is nothing worth asking about.
    """
    if install_mcp is False:
        return Decision.SKIP
    if not interactive:
        return Decision.SKIP
    if install_mcp is True:
        return Decision.PROCEED
    if automatic_approvals or len(detected) == 0:
        return Decision.SKIP
    return Decision.ASK


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


def skills_decision(
    *,
    install_skills: Optional[bool],
    automatic_approvals: bool,
    interactive: bool,
    detected: List[str],
) -> Decision:
    """Whether to install the skill pack, ask first, or leave it alone.

    Mirrors :func:`mcp_decision` deliberately, including the absolute terminal
    requirement — the two steps carry the same consent shape, and the skill pack
    is if anything the more invasive of the two: the MCP step writes credentials
    into a file the user already trusts with them, while this writes instruction
    files the assistant then acts on with its own permissions.
    """
    if install_skills is False:
        return Decision.SKIP
    if not interactive:
        return Decision.SKIP
    if install_skills is True:
        return Decision.PROCEED if detected else Decision.SKIP
    if automatic_approvals or len(detected) == 0:
        return Decision.SKIP
    return Decision.ASK


SKILLS_PROMPT: str = (
    "\n  Recommended: also install the Opik skill pack?\n"
    "  It teaches your AI client how to instrument code with Opik, wire\n"
    "  up integrations, and run test suites. (Y/n) "
)
"""Asked after the server step, so the user answers with its output in front of
them, and recommended — hence the default yes. The clients are not named again
because the server step just listed them."""
