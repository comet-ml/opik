"""Centralised LLM model identifiers used by ``tests/e2e``,
``tests/library_integration`` and ``tests/e2e_library_integration``.

Every model string that a test actually passes to an LLM client lives here.
Names are kept generic (role/family, not version) so bumping a model version
is a one-line change to the value — every test picks it up automatically.

Naming convention
-----------------
* ``<PROVIDER>_<FAMILY>`` — the plain model id as the provider's own client
  expects it (e.g. ``OPENAI_GPT_MINI = "gpt-4o-mini"``). When the provider
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
OPENAI_GPT = "gpt-4o"
OPENAI_GPT_MINI = "gpt-4o-mini"
OPENAI_GPT_LEGACY = "gpt-3.5-turbo"
OPENAI_GPT_NEXT = "gpt-5-mini"
OPENAI_GPT_OSS = "gpt-oss-20b"
OPENAI_SORA = "sora-2"

LITELLM_OPENAI_GPT = f"openai/{OPENAI_GPT}"
LITELLM_OPENAI_GPT_MINI = f"openai/{OPENAI_GPT_MINI}"
LITELLM_OPENAI_GPT_LEGACY = f"openai/{OPENAI_GPT_LEGACY}"
LITELLM_OPENAI_GPT_OSS = f"openai/{OPENAI_GPT_OSS}"

AISUITE_OPENAI_GPT_LEGACY = f"openai:{OPENAI_GPT_LEGACY}"

# ---------------------------------------------------------------------------
# Anthropic
# ---------------------------------------------------------------------------
# Using Anthropic's moving aliases (e.g. "claude-sonnet-4-0") rather than
# pinned timestamped releases so upgrades don't require touching this file
# every few weeks.
ANTHROPIC_CLAUDE_SONNET = "claude-sonnet-4-0"
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
BEDROCK_CLAUDE_SONNET = "us.anthropic.claude-sonnet-4-20250514-v1:0"
BEDROCK_MISTRAL_PIXTRAL = "us.mistral.pixtral-large-2502-v1:0"
BEDROCK_MISTRAL_PIXTRAL_REGION = "us-east-2"

LITELLM_BEDROCK_CLAUDE_SONNET = f"bedrock/{BEDROCK_CLAUDE_SONNET}"
