import logging
from typing import Optional

from opik import semantic_version
from opik.types import DistributedTraceHeadersDict
from . import llm_response_wrapper
from . import litellm_wrappers
from .adk_otel_tracer import opik_adk_otel_tracer

import google.adk.models
from google.adk.models import lite_llm
from google.adk import telemetry as adk_telemetry
from google.adk.agents import base_agent

LOGGER = logging.getLogger(__name__)


def patch_adk(
    distributed_headers: Optional[DistributedTraceHeadersDict] = None,
) -> None:
    _patch_adk_lite_llm()
    if semantic_version.SemanticVersion.parse(google.adk.__version__) >= "1.3.0":  # type: ignore
        _patch_adk_opentelemetry_tracers(
            distributed_headers=distributed_headers,
        )


def _patch_adk_lite_llm() -> None:
    old_function = google.adk.models.LlmResponse.create
    if not hasattr(old_function, "opik_patched"):
        create_wrapper = llm_response_wrapper.LlmResponseCreateWrapper(old_function)
        google.adk.models.LlmResponse.create = create_wrapper
        google.adk.models.LlmResponse.create.opik_patched = True  # type: ignore
        LOGGER.debug("Patched LlmResponse.create")

    if hasattr(lite_llm, "LiteLLMClient") and hasattr(
        lite_llm.LiteLLMClient, "acompletion"
    ):
        if not hasattr(lite_llm.LiteLLMClient.acompletion, "opik_patched"):
            lite_llm.LiteLLMClient.acompletion = (
                litellm_wrappers.litellm_client_acompletion_decorator(
                    lite_llm.LiteLLMClient.acompletion
                )
            )
            lite_llm.LiteLLMClient.acompletion.opik_patched = True  # type: ignore
            LOGGER.debug("Patched LiteLLMClient.acompletion")

    if hasattr(lite_llm, "_model_response_to_generate_content_response"):
        if not hasattr(
            lite_llm._model_response_to_generate_content_response, "opik_patched"
        ):
            lite_llm._model_response_to_generate_content_response = (
                litellm_wrappers.generate_content_response_decorator(
                    lite_llm._model_response_to_generate_content_response
                )
            )
            lite_llm._model_response_to_generate_content_response.opik_patched = True  # type: ignore
            LOGGER.debug("Patched _model_response_to_generate_content_response")


_OPIK_WRAPPER_ATTR = "_opik_wrapper"


def _patch_adk_opentelemetry_tracers(
    distributed_headers: Optional[DistributedTraceHeadersDict],
) -> None:
    # Idempotency guard: if we've already wrapped this tracer (e.g., from a prior
    # OpikTracer.__init__ or from __setstate__ after a deep-copy of the agent),
    # skip re-installing — just refresh the distributed_headers reference so a
    # newly constructed OpikTracer with different headers still takes effect.
    existing_wrapper = getattr(adk_telemetry.tracer, _OPIK_WRAPPER_ATTR, None)
    if existing_wrapper is not None:
        existing_wrapper._distributed_headers = distributed_headers
        return

    inner_start_as_current_span = adk_telemetry.tracer.start_as_current_span
    inner_start_span = adk_telemetry.tracer.start_span

    opik_tracer = opik_adk_otel_tracer.OpikADKOtelTracer(
        inner_start_as_current_span=inner_start_as_current_span,
        inner_start_span=inner_start_span,
        distributed_headers=distributed_headers,
    )

    setattr(adk_telemetry.tracer, _OPIK_WRAPPER_ATTR, opik_tracer)
    adk_telemetry.tracer.start_as_current_span = opik_tracer.start_as_current_span
    adk_telemetry.tracer.start_span = opik_tracer.start_span

    # google-adk >= 1.32 dropped the module-level `tracer` symbol from
    # `google.adk.agents.base_agent` (agent invocation now goes through
    # `google.adk.telemetry._instrumentation.record_agent_invocation`,
    # which calls `telemetry.tracing.tracer` — the same singleton
    # ProxyTracer object we already patched above). On older versions
    # the attribute is still present and points to the same object.
    if hasattr(base_agent, "tracer"):
        base_agent.tracer.start_as_current_span = opik_tracer.start_as_current_span
        base_agent.tracer.start_span = opik_tracer.start_span
    LOGGER.debug("Patched ADK tracers")
