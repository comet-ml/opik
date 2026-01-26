from __future__ import annotations

from dataclasses import dataclass
from typing import Any
from collections.abc import Iterable
import inspect
import logging

from opik import opik_context

from ..api_objects import chat_prompt
from .tool_helpers import deep_merge_dicts, serialize_tools

MIN_OPIK_PROMPT_VERSION = "1.9.40"
logger = logging.getLogger(__name__)


@dataclass(frozen=True)
class PromptTraceSupport:
    """Feature flags for Opik prompt interoperability."""

    supports_prompt_api: bool
    opik_prompt_class: type | None
    opik_chat_prompt_class: type | None


def _get_opik_prompt_support() -> PromptTraceSupport:
    """Detect Opik prompt classes and whether prompt attachments are supported."""
    try:
        import opik  # type: ignore
    except Exception:
        return PromptTraceSupport(False, None, None)

    prompt_class = getattr(opik, "Prompt", None)
    chat_prompt_class = getattr(opik, "ChatPrompt", None)

    if not _is_supported_opik_version(opik):
        return PromptTraceSupport(False, prompt_class, chat_prompt_class)

    if not _has_prompt_signature_support():
        return PromptTraceSupport(False, prompt_class, chat_prompt_class)

    supports_prompt_api = prompt_class is not None and chat_prompt_class is not None
    return PromptTraceSupport(supports_prompt_api, prompt_class, chat_prompt_class)


def _is_supported_opik_version(opik_module: Any) -> bool:
    """Return True if the installed Opik SDK meets minimum prompt-support requirements."""
    version = getattr(opik_module, "__version__", None)
    if not isinstance(version, str):
        return True

    try:
        from opik.semantic_version import SemanticVersion  # type: ignore

        parsed_version = SemanticVersion.parse(version, optional_minor_and_patch=True)
        minimum_version = SemanticVersion.parse(
            MIN_OPIK_PROMPT_VERSION, optional_minor_and_patch=True
        )
        return parsed_version >= minimum_version
    except Exception:
        return True


def _has_prompt_signature_support() -> bool:
    """Return True if the installed Opik context supports the prompts= parameter."""
    try:
        trace_sig = inspect.signature(opik_context.update_current_trace)
        span_sig = inspect.signature(opik_context.update_current_span)
    except Exception:
        return False

    return "prompts" in trace_sig.parameters and "prompts" in span_sig.parameters


def _attach_opik_prompt_source(
    prompt: chat_prompt.ChatPrompt,
    opik_prompt: Any,
    *,
    prompt_type: str,
    source_name: str | None = None,
) -> None:
    """Attach a live Opik prompt object to an optimizer ChatPrompt for UI linking."""
    setattr(prompt, "_opik_prompt_source", opik_prompt)
    setattr(prompt, "_opik_prompt_type", prompt_type)
    if source_name:
        setattr(prompt, "_opik_prompt_source_name", source_name)


def _extract_opik_prompt_info(prompt: chat_prompt.ChatPrompt) -> dict[str, Any] | None:
    """Return Opik prompt info if available (either live object or override)."""
    opik_prompt = getattr(prompt, "_opik_prompt_source", None)
    if opik_prompt is None:
        return None
    try:
        return opik_prompt.__internal_api__to_info_dict__()
    except Exception:
        return None


def _detect_source_type(prompt: chat_prompt.ChatPrompt) -> str:
    """Return the prompt type for display purposes."""
    prompt_type = getattr(prompt, "_opik_prompt_type", None)
    if isinstance(prompt_type, str):
        return prompt_type
    return "chat"


def _extract_system_prompt(messages: list[dict[str, Any]]) -> str | None:
    """Extract the first system prompt string from messages, if any."""
    for message in messages:
        if message.get("role") == "system":
            content = message.get("content")
            if isinstance(content, str) and content.strip():
                return content
    return None


def build_prompt_payload(
    prompt: chat_prompt.ChatPrompt,
    *,
    rendered_messages: list[dict[str, Any]] | None = None,
    include_template: bool = True,
) -> dict[str, Any]:
    """Serialize a ChatPrompt into a tracing payload for metadata/UI display."""
    payload: dict[str, Any] = {
        "name": prompt.name,
        "type": _detect_source_type(prompt),
    }

    if include_template:
        payload["template"] = prompt.to_dict()

    if rendered_messages is not None:
        payload["rendered_messages"] = rendered_messages

    tools = serialize_tools(prompt)
    if tools:
        payload["tools"] = tools

    model = getattr(prompt, "model", None)
    if model:
        payload["model"] = model

    model_parameters = getattr(prompt, "model_kwargs", None)
    if model_parameters:
        payload["model_parameters"] = model_parameters

    opik_prompt_info = _extract_opik_prompt_info(prompt)
    if opik_prompt_info is not None:
        payload["opik_prompt"] = opik_prompt_info

    source_name = getattr(prompt, "_opik_prompt_source_name", None)
    if source_name:
        payload["source_name"] = source_name

    try:
        system_prompt = _extract_system_prompt(prompt.get_messages())
        if system_prompt:
            payload["system_prompt"] = system_prompt
    except Exception:
        pass

    return payload


def build_prompt_payloads(
    prompts: dict[str, chat_prompt.ChatPrompt],
    *,
    rendered_messages: dict[str, list[dict[str, Any]]] | None = None,
) -> list[dict[str, Any]]:
    """Build prompt payloads for multiple prompts."""
    payloads: list[dict[str, Any]] = []
    for name, prompt in prompts.items():
        messages = None
        if rendered_messages is not None:
            messages = rendered_messages.get(name)
        payloads.append(
            build_prompt_payload(
                prompt, rendered_messages=messages, include_template=True
            )
        )
    return payloads


def _merge_trace_metadata(extra_metadata: dict[str, Any]) -> dict[str, Any]:
    """Merge optimizer prompt metadata into the current trace metadata safely."""
    trace_data = opik_context.get_current_trace_data()
    existing_metadata = (
        trace_data.metadata if trace_data and trace_data.metadata else {}
    )
    if not isinstance(existing_metadata, dict):
        existing_metadata = {}
    return deep_merge_dicts(existing_metadata, extra_metadata)


def _merge_span_metadata(extra_metadata: dict[str, Any]) -> dict[str, Any]:
    """Merge optimizer prompt metadata into the current span metadata safely."""
    span_data = opik_context.get_current_span_data()
    existing_metadata = span_data.metadata if span_data and span_data.metadata else {}
    if not isinstance(existing_metadata, dict):
        existing_metadata = {}
    return deep_merge_dicts(existing_metadata, extra_metadata)


def _update_trace(
    *,
    metadata: dict[str, Any] | None = None,
    prompts: Iterable[Any] | None = None,
) -> None:
    """Best-effort update of current trace with metadata/prompts."""
    try:
        if prompts:
            if metadata is not None:
                opik_context.update_current_trace(
                    prompts=list(prompts),
                    metadata=metadata,
                )
            else:
                opik_context.update_current_trace(prompts=list(prompts))
            return
        if metadata is not None:
            opik_context.update_current_trace(metadata=metadata)
    except Exception:
        pass


def _update_span(
    *,
    metadata: dict[str, Any] | None = None,
    prompts: Iterable[Any] | None = None,
) -> None:
    """Best-effort update of current span with metadata/prompts."""
    try:
        if prompts:
            if metadata is not None:
                opik_context.update_current_span(
                    prompts=list(prompts),
                    metadata=metadata,
                )
            else:
                opik_context.update_current_span(prompts=list(prompts))
            return
        if metadata is not None:
            opik_context.update_current_span(metadata=metadata)
    except Exception:
        pass


def normalize_prompt_input(
    prompt_input: chat_prompt.ChatPrompt | dict[str, chat_prompt.ChatPrompt] | Any,
) -> tuple[dict[str, chat_prompt.ChatPrompt], bool]:
    """Normalize prompt input to optimizer ChatPrompt dicts, preserving library links."""
    support = _get_opik_prompt_support()

    if isinstance(prompt_input, dict):
        if all(
            isinstance(prompt, chat_prompt.ChatPrompt)
            for prompt in prompt_input.values()
        ):
            return prompt_input, False

        normalized: dict[str, chat_prompt.ChatPrompt] = {}
        for name, prompt in prompt_input.items():
            normalized[name] = _normalize_single_prompt(
                prompt, support=support, name_override=name
            )
        return normalized, False

    normalized_prompt = _normalize_single_prompt(prompt_input, support=support)
    return {normalized_prompt.name: normalized_prompt}, True


def _normalize_single_prompt(
    prompt: Any,
    *,
    support: PromptTraceSupport,
    name_override: str | None = None,
) -> chat_prompt.ChatPrompt:
    """Normalize a single prompt input into optimizer ChatPrompt."""
    if isinstance(prompt, chat_prompt.ChatPrompt):
        return prompt

    if support.opik_prompt_class and isinstance(prompt, support.opik_prompt_class):
        name = name_override or getattr(prompt, "name", None) or "prompt"
        template = getattr(prompt, "prompt", None)
        if not isinstance(template, str):
            raise TypeError("Opik Prompt template must be a string.")
        normalized = chat_prompt.ChatPrompt(name=name, user=template)
        _attach_opik_prompt_source(
            normalized,
            prompt,
            prompt_type="text",
            source_name=getattr(prompt, "name", None),
        )
        return normalized

    if support.opik_chat_prompt_class and isinstance(
        prompt, support.opik_chat_prompt_class
    ):
        name = name_override or getattr(prompt, "name", None) or "chat-prompt"
        messages = getattr(prompt, "template", None)
        if not isinstance(messages, list):
            raise TypeError("Opik ChatPrompt template must be a list of messages.")
        normalized = chat_prompt.ChatPrompt(name=name, messages=messages)
        _attach_opik_prompt_source(
            normalized,
            prompt,
            prompt_type="chat",
            source_name=getattr(prompt, "name", None),
        )
        return normalized

    raise TypeError(
        "Unsupported prompt type. Expected opik_optimizer.ChatPrompt, opik.Prompt, "
        "or opik.ChatPrompt."
    )


def collect_opik_prompt_sources(
    prompts: dict[str, chat_prompt.ChatPrompt],
) -> list[Any]:
    """Collect attached Opik prompt objects for prompt-aware traces."""
    sources: list[Any] = []
    for prompt in prompts.values():
        source = getattr(prompt, "_opik_prompt_source", None)
        if source is not None:
            sources.append(source)
    return sources


def attach_initial_prompts(prompts: dict[str, chat_prompt.ChatPrompt]) -> None:
    """Attach initial prompt payloads to the current trace metadata."""
    payloads = build_prompt_payloads(prompts)
    metadata = _merge_trace_metadata({"opik_optimizer": {"initial_prompts": payloads}})

    support = _get_opik_prompt_support()
    opik_prompts: list[Any] = []
    if support.supports_prompt_api:
        opik_prompts = collect_opik_prompt_sources(prompts)

    _update_trace(
        metadata=metadata,
        prompts=opik_prompts if support.supports_prompt_api else None,
    )


def record_candidate_prompts(prompts: dict[str, chat_prompt.ChatPrompt]) -> None:
    """Append candidate prompt payloads to trace metadata for optimizer runs."""
    payloads = build_prompt_payloads(prompts)

    trace_data = opik_context.get_current_trace_data()
    existing_metadata = (
        trace_data.metadata if trace_data and trace_data.metadata else {}
    )
    if not isinstance(existing_metadata, dict):
        existing_metadata = {}

    opik_meta = existing_metadata.get("opik_optimizer", {})
    if not isinstance(opik_meta, dict):
        opik_meta = {}

    candidate_prompts = opik_meta.get("candidate_prompts", [])
    if not isinstance(candidate_prompts, list):
        candidate_prompts = []
    candidate_prompts = candidate_prompts + payloads

    opik_meta["candidate_prompts"] = candidate_prompts
    opik_meta["current_prompts"] = payloads

    metadata = deep_merge_dicts(existing_metadata, {"opik_optimizer": opik_meta})
    _update_trace(metadata=metadata)


def attach_span_prompt_payload(
    prompt: chat_prompt.ChatPrompt,
    rendered_messages: list[dict[str, Any]] | None = None,
) -> None:
    """Attach prompt payload metadata to the current span."""
    payloads = [
        build_prompt_payload(
            prompt, rendered_messages=rendered_messages, include_template=True
        )
    ]
    metadata = _merge_span_metadata({"opik_optimizer": {"prompt_payloads": payloads}})

    support = _get_opik_prompt_support()
    opik_prompt = getattr(prompt, "_opik_prompt_source", None)

    _update_span(
        metadata=metadata,
        prompts=[opik_prompt] if support.supports_prompt_api and opik_prompt else None,
    )


# TODO(opik_optimizer): Add optional flag record_prompts_to_library=True to create/version prompts in Opik library.
# TODO(opik_optimizer): Add from_library helpers to load prompt versions by id/commit.
