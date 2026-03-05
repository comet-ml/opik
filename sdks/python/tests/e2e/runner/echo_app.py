"""Minimal agent app used by the runner e2e test.

When executed by the runner, it reads inputs from stdin, echoes the message,
and writes the result to the OPIK_RESULT_FILE.
"""

from opik import track


@track(entrypoint=True)
def echo(message: str) -> str:
    """Echo the input message back."""
    return f"echo: {message}"


if __name__ == "__main__":
    result = echo("hello")
    print(f"Result: {result}")
