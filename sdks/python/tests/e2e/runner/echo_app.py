"""Agent app used by the runner e2e tests.

Contains two entrypoints:
- echo: basic echo function
- echo_config: echo with a configurable greeting (used in mask tests)
"""

import uvicorn
from fastapi import FastAPI

import opik

app = FastAPI()


@app.get("/health")
def health() -> dict:
    return {"status": "ok"}


@app.get("/echo")
@opik.track(entrypoint=True)
def echo(message: str) -> str:
    return f"echo: {message}"


@opik.agent_config()
class EchoConfig:
    greeting: str = "default-greeting"


@app.get("/echo-config")
@opik.track(entrypoint=True)
def echo_config(message: str) -> str:
    cfg = EchoConfig()
    return f"{cfg.greeting}: {message}"


if __name__ == "__main__":
    uvicorn.run(app, host="127.0.0.1", port=0)
