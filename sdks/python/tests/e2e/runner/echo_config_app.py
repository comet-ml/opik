"""Agent app with @agent_config, used by the runner e2e mask test.

The greeting defaults to "default-greeting" but can be overridden via
a mask passed through OPIK_MASK_ID at job creation time.
"""

import opik
from opik.runner.activate import activate_runner


@opik.agent_config()
class EchoConfig:
    greeting: str = "default-greeting"


@opik.track(entrypoint=True)
def echo_config(message: str) -> str:
    """Echo with a configurable greeting."""
    cfg = EchoConfig()
    return f"{cfg.greeting}: {message}"


activate_runner()

if __name__ == "__main__":
    result = echo_config("hello")
    print(f"Result: {result}")
