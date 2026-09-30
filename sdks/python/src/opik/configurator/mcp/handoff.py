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

import logging
import os
import shutil
import signal
import subprocess
import sys
from typing import Dict, Final, List, Optional

import httpx

import opik.httpx_client as httpx_client
import opik.url_helpers as url_helpers

LOGGER = logging.getLogger(__name__)

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
    api_key: Optional[str],
    workspace: Optional[str],
    api_url: str,
    check_tls_certificate: bool,
) -> Optional[str]:
    """A project of the user's own that already has traces, if there is one.

    Best-effort: any failure answers "none", which sends the user to the
    instrument skill. That is the safer way to be wrong — it is the right first
    step for someone with nothing logged, and merely redundant for someone who
    has already instrumented their app.
    """
    try:
        with httpx_client.get(
            workspace=workspace,
            api_key=api_key,
            check_tls_certificate=check_tls_certificate,
            compress_json_requests=False,
        ) as client:
            response = client.get(
                url=f"{url_helpers.ensure_ending_slash(api_url)}v1/private/projects",
                params={"page": 1, "size": PROJECT_PAGE_SIZE},
                timeout=PROJECTS_TIMEOUT_SECONDS,
            )
    except (httpx.HTTPError, OSError):
        LOGGER.debug("Could not list projects for the closing prompt", exc_info=True)
        return None

    if response.status_code != 200:
        return None

    try:
        body = response.json()
    except ValueError:
        return None

    # Shape-checked rather than trusted: this runs just before the result event,
    # and an `AttributeError` here would take the command down without reporting
    # anything. A body that is not what we expect means "no project", like every
    # other failure in this function.
    if not isinstance(body, dict):
        return None
    content = body.get("content", [])
    if not isinstance(content, list):
        return None

    return _first_traced_project(content)


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


def can_launch(host_key: str) -> bool:
    """Whether this client can be started here, with the prompt already in it."""
    command = LAUNCH_COMMANDS.get(host_key)
    return command is not None and shutil.which(command[0]) is not None


def launch(host_key: str, prompt: str) -> None:
    """Replace this process with the agent, mid-question.

    The prompt goes in as the positional argument, which both CLIs treat as a
    message to send — so the agent opens already working on it. That is the
    point: the user was shown this exact prompt and said yes to it a line ago.

    ``execvp`` rather than a subprocess: the agent owns the terminal from here,
    and a parent sitting behind it would only be something to exit twice. It
    also means nothing after this line runs, which is why the caller flushes
    what it has to say first.
    """
    command = LAUNCH_COMMANDS.get(host_key)
    if command is None:
        return

    executable = shutil.which(command[0])
    if executable is None:
        return

    if sys.platform == "win32":
        # Windows has no exec. `os.execvp` there starts the agent and exits this
        # process, handing the console back to the shell while the agent is still
        # reading from it, and passes the arguments unquoted, so the prompt
        # arrives split into words. The nearest equivalent is to run the agent as
        # a child and leave with its status — ignoring Ctrl-C meanwhile, which
        # the agent uses for itself and would otherwise take this process down,
        # and the agent with it.
        signal.signal(signal.SIGINT, signal.SIG_IGN)
        sys.exit(subprocess.run([executable, *command[1:], prompt]).returncode)

    os.execvp(executable, [*command, prompt])
