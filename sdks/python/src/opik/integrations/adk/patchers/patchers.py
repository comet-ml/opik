import logging

from opik import semantic_version
from opik.api_objects import opik_client
from . import llm_response_wrapper
from . import litellm_wrappers
from . import adk_tracer_for_opik_context_management

import google.adk.models
from google.adk.models import lite_llm
from google.adk import telemetry as adk_telemetry
from google.adk.agents import base_agent

LOGGER = logging.getLogger(__name__)


def patch_adk(opik_client: opik_client.Opik) -> None:
    _patch_adk_lite_llm()
    if semantic_version.SemanticVersion.parse(google.adk.__version__) >= "1.3.0":  # type: ignore
        _patch_adk_opentelemetry_tracers(opik_client)


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


def _patch_adk_opentelemetry_tracers(opik_client: opik_client.Opik) -> None:
    no_op_opik_tracer = (
        adk_tracer_for_opik_context_management.ADKTracerForOpikContextManagement(
            opik_client
        )
    )

    adk_telemetry.tracer.start_as_current_span = no_op_opik_tracer.start_as_current_span
    adk_telemetry.tracer.start_span = no_op_opik_tracer.start_span

    base_agent.tracer.start_as_current_span = no_op_opik_tracer.start_as_current_span
    base_agent.tracer.start_span = no_op_opik_tracer.start_span
    LOGGER.debug("Patched ADK tracers")
