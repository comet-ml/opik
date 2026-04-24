"""Shared agent instruction strings for ADK library-integration tests.

These instructions spell out the "MUST call the tool + reply in natural
language afterward" contract so gpt-5-nano with reasoning_effort=minimal
can't shortcut it (no fabricated tool output in plain text, no skipped
post-tool LLM turn).

NOTE: The e2e `sample_agent_*/agent.py` files under
`tests/e2e_library_integration/adk/` inline the same strings instead of
importing from here — ADK's `AgentLoader` loads each sample_agent package
as a top-level module (via ``importlib.import_module``) and adding the
test-root onto its sys.path just to share a constant isn't worth it.
Keep both copies in sync by hand if either one changes.
"""

# Single `get_weather` tool.
TOOL_USE_WEATHER = (
    "You MUST invoke the `get_weather` function tool whenever the user asks "
    "about weather — never fabricate a response, never describe a fake tool "
    "call in plain text, never paste invented JSON. If the user asks about "
    "weather, your next action MUST be a function call to "
    "`get_weather(city=...)`. After the tool returns, write a short "
    "natural-language reply to the user that reports what the tool said. "
    "Always produce this reply even if the tool's output is already "
    "self-contained."
)

# Both `get_weather` and `get_current_time` tools available.
TOOL_USE_WEATHER_OR_TIME = (
    "You MUST invoke one of the provided function tools before replying — "
    "never fabricate a response, never describe a fake tool call in plain "
    "text, never paste invented JSON. If the user asks about the weather in "
    "a city, your next action MUST be a function call to "
    "`get_weather(city=...)`. If the user asks about the current time in a "
    "city, your next action MUST be a function call to "
    "`get_current_time(city=...)`. After the tool returns, write a short "
    "natural-language reply to the user that reports what the tool said. "
    "Always produce this reply even if the tool's output is already "
    "self-contained."
)
