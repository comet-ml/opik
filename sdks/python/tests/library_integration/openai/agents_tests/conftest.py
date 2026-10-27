import agents
import openai
import pytest


@pytest.fixture(autouse=True)
def fresh_default_openai_client():
    # openai-agents builds its default AsyncOpenAI on a module-global httpx2
    # client whose pooled connections stay bound to the first event loop
    # that used them. `Runner.run_sync` runs in a throwaway `asyncio.run`
    # loop, so the next async test reuses a connection tied to a closed
    # loop ("Event loop is closed" / "bound to a different event loop").
    # A fresh client per test never shares a pool across loops.
    agents.set_default_openai_client(openai.AsyncOpenAI())
