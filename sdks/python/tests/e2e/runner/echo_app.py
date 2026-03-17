"""Agent app used by the runner e2e tests.

Contains two entrypoints:
- echo: basic echo function
- echo_config: echo with a configurable greeting (used in mask tests)
"""

import opik
from opik.runner.activate import activate_runner


@opik.track(entrypoint=True)
def echo(message: str) -> str:
    return f"echo: {message}"


@opik.agent_config()
class EchoConfig:
    greeting: str = "default-greeting"


@opik.track(entrypoint=True)
def echo_config(message: str) -> str:
    cfg = EchoConfig()
    return f"{cfg.greeting}: {message}"


activate_runner()

if __name__ == "__main__":
    result = echo("hello")
    print(f"Result: {result}")
