from opik.integrations.crewai import opik_tracker

from ... import llm_constants
from ...testlib import ANY_BUT_NONE

PROJECT_NAME = "crewai-test"

# CrewAI v0 still runs against gpt-4o-mini: its pinned litellm==1.74.9
# reports `stop` as supported for gpt-5-nano and then CrewAI's ReAct loop
# injects stop tokens the OpenAI API rejects. gpt-4o-mini dodges that.
# v1 standardises on gpt-5-nano like the rest of the suite.
# reasoning_effort only applies to gpt-5-nano; gpt-4o-mini rejects it.
if opik_tracker.is_crewai_v1():
    OPENAI_MODEL = llm_constants.LITELLM_OPENAI_GPT_NANO
    OPENAI_MODEL_KWARGS = {"reasoning_effort": llm_constants.OPENAI_REASONING_EFFORT}
else:
    OPENAI_MODEL = llm_constants.LITELLM_OPENAI_GPT_4O_MINI
    OPENAI_MODEL_KWARGS = {}

EXPECTED_SHORT_OPENAI_USAGE_LOGGED_FORMAT = {
    "prompt_tokens": ANY_BUT_NONE,
    "completion_tokens": ANY_BUT_NONE,
    "total_tokens": ANY_BUT_NONE,
    # original usage is not asserted
}
