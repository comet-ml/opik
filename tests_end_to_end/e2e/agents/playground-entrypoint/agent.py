"""Entrypoint-decorated golden agent for the Agent playground E2E test.

A small, deterministic agent with a typed `run(query: str) -> str` entrypoint
marked with `@opik.track(entrypoint=True)`. The Agent playground page pairs
against an `opik endpoint` runner, which introspects the entrypoint to expose
its params in the playground's Test-input form and to invoke it from the Run
button. The uninstrumented sibling agent (no entrypoint) is reserved for the
Ollie /instrument flow.

The script blocks on a long sleep so the `opik endpoint` runner — which
processes jobs from a background thread in the same process — stays alive long
enough for the test to drive a run through it. Deliberately dependency-free and
offline; the output is deterministic so the test can match on it.
"""

import time

import opik


@opik.track(entrypoint=True, project_name="agent-playground-e2e")
def run(query: str) -> str:
    return f"Answer for: {query}"


if __name__ == "__main__":
    # Stay alive so the runner thread can serve jobs. The test stops the daemon
    # in a finally block, which terminates this process via the parent.
    time.sleep(3600)
