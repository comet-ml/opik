"""Agent app used by the runner e2e tests.

Contains two entrypoints:
- echo: basic echo function
- echo_config: echo with a configurable greeting (used in mask tests)
"""

import threading

import opik


@opik.track(entrypoint=True)
def echo(message: str) -> str:
    return f"echo: {message}"


class EchoConfig(opik.AgentConfig):
    greeting: str


@opik.track(entrypoint=True)
def echo_config(message: str) -> str:
    client = opik.Opik()
    version = client.create_agent_config_version(
        EchoConfig(greeting="default-greeting")
    )
    cfg = client.get_agent_config(
        fallback=EchoConfig(greeting="fallback-greeting"), version=version
    )
    return f"{cfg.greeting}: {message}"


if __name__ == "__main__":
    # Analogous to uvicorn.run() in a FastAPI app: @opik.track(entrypoint=True) registers
    # handlers at import time, and this blocks the main thread so the runner loop stays
    # alive to process jobs.
    threading.Event().wait()
