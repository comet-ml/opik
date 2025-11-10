"""
Tools for rendering chat-style prompts with multimodal content.

The template mirrors :class:`PromptTemplate` but works on a list of OpenAI-like
messages. Rendering is handled by a registry of part renderers so additional
modalities can be plugged in without changing the core implementation.
"""

from __future__ import annotations

import re
from typing import Any, Dict, List, Optional, Set, Union, cast
from typing_extensions import override

import opik.exceptions as exceptions

from ..string import prompt_template
from .. import types as prompt_types
from .. import base_prompt_template
from . import content_renderer_registry


class ChatPromptTemplate(base_prompt_template.BasePromptTemplate):
    """
    Prompt template for chat-style prompts with multimodal content.
    """

    def __init__(
        self,
        messages: List[Dict[str, prompt_types.MessageContent]],
        template_type: prompt_types.PromptType = prompt_types.PromptType.MUSTACHE,
        *,
        registry: Optional[
            content_renderer_registry.ChatContentRendererRegistry
        ] = None,
        validate_placeholders: bool = True,
    ) -> None:
        self._messages = messages
        self._template_type = template_type
        self._registry = (
            registry or content_renderer_registry.DEFAULT_CHAT_RENDERER_REGISTRY
        )
        self._validate_placeholders = validate_placeholders

    @property
    def messages(self) -> List[Dict[str, prompt_types.MessageContent]]:
        return self._messages

    def required_modalities(self) -> prompt_types.ModalitySet:
        """
        Return the union of modalities referenced across all template messages.
        """
        required: prompt_types.ModalitySet = set()
        for message in self._messages:
            content = cast(prompt_types.MessageContent, message.get("content", ""))
            required.update(self._registry.infer_modalities(content))
        return required

    def _extract_placeholders(self, template_type: prompt_types.PromptType) -> Set[str]:
        """
        Extract all placeholders from all messages.
        """
        placeholders: Set[str] = set()
        for message in self._messages:
            content = cast(prompt_types.MessageContent, message.get("content", ""))
            if isinstance(content, str):
                placeholders.update(
                    _extract_placeholders_from_string(content, template_type)
                )
            elif isinstance(content, list):
                for part in content:
                    if not isinstance(part, dict):
                        continue
                    # Extract from text parts
                    if "text" in part:
                        text = str(part["text"])
                        placeholders.update(
                            _extract_placeholders_from_string(text, template_type)
                        )
                    # Extract from image_url parts
                    if "image_url" in part and isinstance(part["image_url"], dict):
                        url = str(part["image_url"].get("url", ""))
                        placeholders.update(
                            _extract_placeholders_from_string(url, template_type)
                        )
        return placeholders

    @override
    def format(
        self,
        variables: Dict[str, Any],
        supported_modalities: Optional[prompt_types.SupportedModalities] = None,
        *,
        template_type: Optional[Union[str, prompt_types.PromptType]] = None,
    ) -> List[Dict[str, prompt_types.MessageContent]]:
        """
        Render the template messages with the provided variables.

        When a part declares a modality that is not supported, the registry replaces
        it with the configured placeholder pair (for example ``<<<image>>>``) so
        downstream consumers receive a textual anchor while unsupported structured
        content is gracefully elided.
        """
        resolved_template_type = self._registry.normalize_template_type(
            template_type or self._template_type
        )

        # Validate placeholders if enabled and using Mustache templates
        if (
            self._validate_placeholders
            and resolved_template_type == prompt_types.PromptType.MUSTACHE
        ):
            placeholders = self._extract_placeholders(resolved_template_type)
            variables_keys: Set[str] = set(variables.keys())

            if variables_keys != placeholders:
                raise exceptions.PromptPlaceholdersDontMatchFormatArguments(
                    prompt_placeholders=placeholders, format_arguments=variables_keys
                )

        rendered_messages: List[Dict[str, prompt_types.MessageContent]] = []

        for message in self._messages:
            role = message.get("role")
            if role is None:
                continue

            content = cast(prompt_types.MessageContent, message.get("content", ""))
            rendered_content: prompt_types.MessageContent
            if isinstance(content, str):
                rendered_content = _render_template_string(
                    content, variables, resolved_template_type
                )
            else:
                rendered_content = self._registry.render_content(
                    content=cast(prompt_types.MessageContent, content),
                    variables=variables,
                    template_type=resolved_template_type,
                    supported_modalities=supported_modalities,
                )
            rendered_messages.append(
                {
                    "role": role,
                    "content": rendered_content,
                }
            )

        return rendered_messages


def _render_template_string(
    template: str,
    variables: Dict[str, Any],
    template_type: prompt_types.PromptType,
) -> str:
    if not template:
        return ""

    try:
        return prompt_template.PromptTemplate(
            template,
            validate_placeholders=False,
            type=template_type,
        ).format(**variables)
    except Exception:
        # Fall back to the raw template if formatting fails so evaluation keeps running.
        return template


def render_text_part(
    part: prompt_types.ContentPart,
    variables: Dict[str, Any],
    template_type: prompt_types.PromptType,
) -> Optional[prompt_types.ContentPart]:
    text_template = part.get("text", "")
    rendered_text = _render_template_string(text_template, variables, template_type)
    return {"type": "text", "text": rendered_text}


def render_image_url_part(
    part: prompt_types.ContentPart,
    variables: Dict[str, Any],
    template_type: prompt_types.PromptType,
) -> Optional[prompt_types.ContentPart]:
    image_dict = part.get("image_url", {})
    if not isinstance(image_dict, dict):
        return None

    url_template = image_dict.get("url", "")
    rendered_url = _render_template_string(url_template, variables, template_type)
    if not rendered_url:
        return None

    rendered_image: Dict[str, Any] = {"url": rendered_url}
    if "detail" in image_dict:
        rendered_image["detail"] = image_dict["detail"]

    return {"type": "image_url", "image_url": rendered_image}


def _extract_placeholders_from_string(
    text: str, template_type: prompt_types.PromptType
) -> Set[str]:
    """
    Extract placeholder keys from a string template.
    Only supports Mustache templates for now.
    """
    if template_type == prompt_types.PromptType.MUSTACHE:
        pattern = r"\{\{(.*?)\}\}"
        return set(re.findall(pattern, text))
    return set()


content_renderer_registry.register_default_chat_part_renderer("text", render_text_part)
content_renderer_registry.register_default_chat_part_renderer(
    "image_url",
    render_image_url_part,
    modality="vision",
    placeholder=("<<<image>>>", "<<</image>>>"),
)
