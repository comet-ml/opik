"""Centralised LLM model identifiers for the opik-python-backend tests.

Every real model string the tests pass through the studio pipeline lives here,
so bumping a model version is a one-line change and the unit + e2e suites stay
in sync. Mirrors the convention in the Python SDK's ``tests/llm_constants.py``.
"""

# Anthropic — the task/prompt model used by both the unit and e2e suites. It
# resolves to the workspace Anthropic key server-side via the gateway.
ANTHROPIC_CLAUDE_HAIKU = "claude-haiku-4-5-20251001"
# Short prefix for asserting the model in traces (the full id carries a date
# suffix that may change).
ANTHROPIC_CLAUDE_HAIKU_SHORT = "claude-haiku-4"
# A larger model, used to verify a separate algorithm/optimizer model.
ANTHROPIC_CLAUDE_OPUS = "claude-opus-4-8"

# The opik_optimizer SDK default. The model-passing regression fell back to it,
# so tests assert it never leaks into a run's traces.
OPENAI_GPT_NANO = "gpt-5-nano"

# The studio routes every LLM call through the backend gateway, which litellm
# addresses with an "openai/"-prefixed model id regardless of the real provider.
GATEWAY_MODEL_PREFIX = "openai/"
GATEWAY_CLAUDE_HAIKU = f"{GATEWAY_MODEL_PREFIX}{ANTHROPIC_CLAUDE_HAIKU}"
GATEWAY_CLAUDE_OPUS = f"{GATEWAY_MODEL_PREFIX}{ANTHROPIC_CLAUDE_OPUS}"
