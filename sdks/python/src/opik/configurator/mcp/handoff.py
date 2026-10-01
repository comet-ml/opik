"""How ``opik mcp configure`` ends: inside the agent, on a question worth asking.

Registering a server is not the point — using it is. So the command finishes by
starting the client it just configured on a prompt that exercises the thing that
was set up, rather than printing "done" and leaving the user to think of
something.

The prompt is asked, not merely offered. It is shown with the question that
precedes it — "Continue in Claude Code" — so by the time the agent starts, the
user has read what it will be asked and agreed to it.

Which prompt depends on what the user already has. Traces of their own mean
there is something to look at, and the diagnose skill is what looks at it. No
traces means nothing to diagnose yet, so the instrument skill is the honest next
step. Demo projects do not count: every new workspace has them, and pointing an
agent at data the user did not produce teaches them nothing about their own app.
"""

import os
import shutil
import signal
import subprocess
import sys
from typing import Dict, Final, List, Optional

from opik.configurator import opik_rest_helpers


#: Mirrors `DemoData.PROJECTS` in the backend, which is the list the product
#: itself excludes when it asks "has this workspace done anything yet".
DEMO_PROJECT_NAMES: Final[frozenset] = frozenset(
    {
        "Demo evaluation",
        "Demo chatbot 🤖",
        "Opik Demo Agent Observability",
        "Opik Demo Assistant",
        "Opik Demo Optimizer",
    }
)

#: One page is enough to answer a yes/no question about a workspace.
PROJECT_PAGE_SIZE: Final[int] = 100
PROJECTS_TIMEOUT_SECONDS: Final[float] = 5.0

DIAGNOSE_PROMPT: Final[str] = (
    "Using the Opik /opik-diagnose skill, give me an overview of my {project} "
    "project over the last 7 days — volume, errors, latency and cost — and then "
    "the items worth my attention, with what makes each one stand out."
)

INSTRUMENT_PROMPT: Final[str] = (
    "Using the Opik /opik-instrument skill, add Opik tracing to this app. If it "
    "is already instrumented, tell me what is covered and what is not."
)

#: The clients that take a prompt as an argument and run in this terminal. A GUI
#: client cannot be handed one, so it is left to the caller to show.
LAUNCH_COMMANDS: Final[Dict[str, List[str]]] = {
    "claude-code": ["claude"],
    "codex": ["codex"],
}


def traced_project(
    api_key: Optional[str], workspace: Optional[str], api_url: str
) -> Optional[str]:
    """A project of the user's own that already has traces, if there is one.

    Any failure answers "none", which sends the user to the instrument skill:
    the right first step with nothing logged, and merely redundant otherwise.
    """
    projects = opik_rest_helpers.list_projects(
        api_key=api_key,
        workspace=workspace,
        api_url=api_url,
        params={"page": 1, "size": PROJECT_PAGE_SIZE},
        timeout=PROJECTS_TIMEOUT_SECONDS,
    )
    return None if projects is None else _first_traced_project(projects)


def _first_traced_project(projects: List[dict]) -> Optional[str]:
    """The most recently traced project of the user's own, within ``projects``.

    ``last_updated_trace_at`` is the field that says a project has traces at all;
    it stays null until the first one arrives.

    Within the page the caller fetched, not within the workspace: past a hundred
    projects the newest traced one can fall outside it. The cost of being wrong
    is naming an older project of the user's own in the closing prompt, which is
    still a project with traces in it — not worth paginating a workspace for.
    """
    candidates = [
        project
        for project in projects
        if isinstance(project, dict)
        and project.get("last_updated_trace_at")
        and project.get("name") not in DEMO_PROJECT_NAMES
    ]
    if not candidates:
        return None

    most_recent = max(candidates, key=lambda project: project["last_updated_trace_at"])
    name = most_recent.get("name")
    return name if isinstance(name, str) else None


def closing_prompt(project: Optional[str]) -> str:
    """The question the agent opens with."""
    if project is None:
        return INSTRUMENT_PROMPT
    return DIAGNOSE_PROMPT.format(project=project)


def launch_command(host_key: str) -> Optional[List[str]]:
    """The command that starts this client here, or None for one that cannot be.

    A GUI client cannot be handed a prompt from a terminal, and a terminal one
    is only startable when its CLI is on PATH.
    """
    command = LAUNCH_COMMANDS.get(host_key)
    executable = shutil.which(command[0]) if command is not None else None
    if command is None or executable is None:
        return None
    return [executable, *command[1:]]


def launch(command: List[str], prompt: str) -> None:
    """Replace this process with the agent, already working on ``prompt``.

    Both CLIs treat a positional argument as a message to send, and the user
    agreed to this exact prompt a line ago. ``execvp`` rather than a child
    process: the agent owns the terminal from here, and nothing after this
    line runs, which is why the caller flushes analytics first.
    """
    if sys.platform == "win32":
        # Windows has no exec: `os.execvp` exits this process while the agent
        # still reads the console, and passes the prompt unquoted. Run it as a
        # child instead, ignoring the Ctrl-C the agent uses for itself.
        signal.signal(signal.SIGINT, signal.SIG_IGN)
        sys.exit(subprocess.run([*command, prompt]).returncode)

    os.execvp(command[0], [*command, prompt])
