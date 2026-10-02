"""How ``opik mcp configure`` ends: inside the agent, on a prompt the user agreed to.

With an API key this process looks at the workspace itself: traces of the user's
own get the diagnose prompt, none get the instrument one. Demo projects do not
count — every workspace starts with them. Without a key (Cloud signed in over
OAuth) it cannot look, so the agent, signed in by then, is asked to check and pick.
"""

import os
import shutil
import signal
import subprocess
import sys
from typing import Dict, Final, List, Optional

from opik.configurator import opik_rest_helpers


#: Mirrors `DemoData.PROJECTS` in the backend.
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

CHECK_FIRST_PROMPT: Final[str] = (
    "Using Opik, check whether my workspace already has traces from my own "
    "projects (ignore the Opik demo projects). If it does, use the /opik-diagnose "
    "skill to give me an overview of the most recently active one over the last 7 "
    "days — volume, errors, latency and cost — and the items worth my attention. "
    "If it does not, use the /opik-instrument skill to add Opik tracing to this app."
)

#: Clients that run in this terminal and take a prompt as an argument.
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
    """The most recently traced project of the user's own, within this page.

    Past a hundred projects the newest can fall outside the page; naming an
    older traced project is an acceptable miss for a closing prompt.
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
    """The command that starts this client here, or None if it cannot be."""
    command = LAUNCH_COMMANDS.get(host_key)
    executable = shutil.which(command[0]) if command is not None else None
    if command is None or executable is None:
        return None
    return [executable, *command[1:]]


def launch(command: List[str], prompt: str) -> None:
    """Replace this process with the agent, already working on ``prompt``.

    Nothing after this runs, which is why the caller flushes analytics first.
    """
    if sys.platform == "win32":
        # Windows has no real exec, and `execvp` there passes the prompt unquoted.
        # Run the agent as a child, leaving Ctrl-C to it.
        signal.signal(signal.SIGINT, signal.SIG_IGN)
        sys.exit(subprocess.run([*command, prompt]).returncode)

    os.execvp(command[0], [*command, prompt])
