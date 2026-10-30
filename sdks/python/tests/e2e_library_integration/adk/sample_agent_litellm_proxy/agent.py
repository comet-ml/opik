import os
from typing import Any

from google.adk.agents import LlmAgent
from google.adk.agents.callback_context import CallbackContext
from google.adk.models.lite_llm import LiteLlm

from opik.integrations.adk import OpikTracer

# ADK loads this file as a top-level module, so relative imports above this
# package are unavailable - keep these inline. They must stay in sync with the
# constants of the same name in test_opik_tracer.py.
PROXY_PORT = int(os.environ.get("LITELLM_PROXY_PORT", "21346"))
PROXY_ALIAS = "internal-proxy-alias"
PROXY_MASTER_KEY = "sk-opik-e2e-proxy"


opik_tracer = OpikTracer()


def after_agent_callback(
    callback_context: CallbackContext, *args: Any, **kwargs: Any
) -> None:
    opik_tracer.after_agent_callback(callback_context, *args, **kwargs)
    opik_tracer.flush()


# The point of this agent: a proxy alias Opik cannot price. "litellm_proxy" is not
# an opik.LLMProvider, and the proxy echoes the alias back as the model name, so the
# span's only possible cost is the one the proxy itself computed and returned in the
# x-litellm-response-cost header.
root_agent = LlmAgent(
    name="proxy_cost_agent",
    model=LiteLlm(
        model=f"litellm_proxy/{PROXY_ALIAS}",
        api_base=f"http://localhost:{PROXY_PORT}",
        api_key=PROXY_MASTER_KEY,
        max_tokens=32,
    ),
    description="Agent used to verify that a LiteLLM proxy's cost reaches Opik.",
    instruction="Answer the user's question in one short sentence.",
    before_agent_callback=opik_tracer.before_agent_callback,
    after_agent_callback=after_agent_callback,
    before_model_callback=opik_tracer.before_model_callback,
    after_model_callback=opik_tracer.after_model_callback,
)
