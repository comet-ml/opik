"""Minimal agent app used by the runner e2e test.

When executed by the runner, it reads inputs from stdin, echoes the message,
and writes the result to the OPIK_RESULT_FILE.
"""

import opik
from opik.runner.activate import activate_runner


@opik.track(entrypoint=True)
def echo(message: str) -> str:
    """Echo the input message back."""
    return f"echo: {message}"


activate_runner()

if __name__ == "__main__":
    result = echo("hello")
    print(f"Result: {result}")
