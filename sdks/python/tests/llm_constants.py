"""Centralised LLM model identifiers used by ``tests/e2e``,
``tests/library_integration`` and ``tests/e2e_library_integration``.

Every model string that a test actually passes to an LLM client lives here.
Names are kept generic (role/family, not version) so bumping a model version
is a one-line change to the value — every test picks it up automatically.

Naming convention
-----------------
* ``<PROVIDER>_<FAMILY>`` — the plain model id as the provider's own client
  expects it (e.g. ``OPENAI_GPT_NANO = "gpt-5-nano"``). When the provider
  bumps the family we just bump the value here.
* ``LITELLM_<PROVIDER>_<FAMILY>`` — the ``provider/model`` form LiteLLM,
  DSPy and CrewAI use (derived from the plain constant).
* ``AISUITE_<PROVIDER>_<FAMILY>`` — the ``provider:model`` form AISuite uses.

Labels used purely as test metadata (e.g. ``experiment_config={"model_name":
"gpt-3.5"}``) are NOT centralised — they're not real model calls, just
strings the backend stores verbatim, so keeping them inline preserves
readability and avoids false-coupling with real model identifiers.
"""

# ---------------------------------------------------------------------------
# OpenAI
# ---------------------------------------------------------------------------
# Default chat model: gpt-5-nano — the latest cheap+fast tier. gpt-4o-mini is
# on the sunset path. When OpenAI bumps the "nano" tier again we only update
# the value here.
OPENAI_GPT_NANO = "gpt-5-nano"
OPENAI_SORA = "sora-2"

# gpt-4o-mini kept only for CrewAI v0 — v0's hard pin on litellm==1.74.9
# reports `stop` as supported for gpt-5-nano, which CrewAI's ReAct loop
# then injects and the OpenAI API rejects. gpt-4o-mini dodges that. Drop
# this constant once CrewAI v0 support is removed or once OpenAI sunsets
# gpt-4o-mini.
OPENAI_GPT_4O_MINI = "gpt-4o-mini"

LITELLM_OPENAI_GPT_NANO = f"openai/{OPENAI_GPT_NANO}"
LITELLM_OPENAI_GPT_4O_MINI = f"openai/{OPENAI_GPT_4O_MINI}"
AISUITE_OPENAI_GPT_NANO = f"openai:{OPENAI_GPT_NANO}"

# gpt-5 family members are reasoning models — they spend `max_tokens` on
# internal reasoning before emitting visible content. Tests that cap
# `max_tokens` must pass this to leave room for actual output; we use the
# lowest supported setting so reasoning overhead stays minimal.
OPENAI_REASONING_EFFORT = "minimal"

# ---------------------------------------------------------------------------
# Anthropic
# ---------------------------------------------------------------------------
# Using Anthropic's moving aliases (e.g. "claude-sonnet-4-0") rather than
# pinned timestamped releases so upgrades don't require touching this file
# every few weeks.
ANTHROPIC_CLAUDE_SONNET = "claude-sonnet-4-6"
ANTHROPIC_CLAUDE_HAIKU = "claude-haiku-4-5-20251001"

LITELLM_ANTHROPIC_CLAUDE_SONNET = f"anthropic/{ANTHROPIC_CLAUDE_SONNET}"
LITELLM_ANTHROPIC_CLAUDE_HAIKU = f"anthropic/{ANTHROPIC_CLAUDE_HAIKU}"

AISUITE_ANTHROPIC_CLAUDE_SONNET = f"anthropic:{ANTHROPIC_CLAUDE_SONNET}"

# ---------------------------------------------------------------------------
# Google Gemini
# ---------------------------------------------------------------------------
# Current default: Gemini 2.5 Flash — cheap, fast, widely supported.
GEMINI_FLASH = "gemini-2.5-flash"

# ---------------------------------------------------------------------------
# AWS Bedrock
# ---------------------------------------------------------------------------
BEDROCK_CLAUDE_SONNET = "us.anthropic.claude-sonnet-4-6"
BEDROCK_MISTRAL_PIXTRAL = "us.mistral.pixtral-large-2502-v1:0"
BEDROCK_MISTRAL_PIXTRAL_REGION = "us-east-2"

LITELLM_BEDROCK_CLAUDE_SONNET = f"bedrock/{BEDROCK_CLAUDE_SONNET}"
