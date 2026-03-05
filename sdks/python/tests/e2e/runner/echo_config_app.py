"""Agent app with @agent_config, used by the runner e2e mask test.

The greeting defaults to "default-greeting" but can be overridden via
a mask passed through OPIK_MASK_ID at job creation time.
"""

from opik import track, agent_config


@agent_config()
class EchoConfig:
    greeting: str = "default-greeting"


@track(entrypoint=True)
def echo_config(message: str) -> str:
    """Echo with a configurable greeting."""
    cfg = EchoConfig()
    return f"{cfg.greeting}: {message}"


if __name__ == "__main__":
    result = echo_config("hello")
    print(f"Result: {result}")
