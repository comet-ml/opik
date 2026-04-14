"""Agent app used by the runner e2e tests.

Contains two entrypoints:
- echo: basic echo function
- echo_config: echo with a configurable greeting (used in mask tests)
"""

import threading

import opik


@opik.track(entrypoint=True)
def echo(message: str) -> str:
    print(f"echo stdout: {message}")
    return f"echo: {message}"


class EchoConfig(opik.Config):
    greeting: str


@opik.track(entrypoint=True)
def echo_config(message: str) -> str:
    client = opik.Opik()
    cfg = client.get_or_create_config(
        fallback=EchoConfig(greeting="fallback-greeting"),
    )
    return f"{cfg.greeting}: {message}"


if __name__ == "__main__":
    # Analogous to uvicorn.run() in a FastAPI app: @opik.track(entrypoint=True) registers
    # handlers at import time, and this blocks the main thread so the runner loop stays
    # alive to process jobs.
    threading.Event().wait()
