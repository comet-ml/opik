"""
Utilities for rendering evaluation message content with extensible multimodal support.

The renderer accepts both plain string prompts and OpenAI-style structured content.
When the target model lacks support for a modality (for example vision), structured
parts are flattened into textual placeholders so downstream tooling can still reason
about the referenced media. The registry is intentionally designed to be extended to
future modalities.
"""

from __future__ import annotations

from typing import (
    Any,
    Callable,
    Dict,
    List,
    Mapping,
    MutableMapping,
    Optional,
    Tuple,
    Union,
)

from opik.api_objects.prompt.prompt_template import PromptTemplate
from opik.api_objects.prompt.types import PromptType

MessageContent = Union[str, List[Dict[str, Any]]]
ContentPart = Dict[str, Any]
RendererFn = Callable[[ContentPart, Dict[str, Any], PromptType], Optional[ContentPart]]


class MessageContentRenderer:
    """
    Registry-backed renderer for evaluation message content.

    Use :meth:`render` to format OpenAI-style messages while respecting model
    capabilities. The default registration covers text blocks and image URLs,
    including HTTP links and base64-encoded PNG/JPEG data URIs. Custom parts
    can be registered via :meth:`register_part_renderer`.
    """

    _part_renderers: MutableMapping[str, RendererFn] = {}
    _part_modalities: MutableMapping[str, Optional[str]] = {}
    _modality_placeholders: MutableMapping[str, Tuple[str, str]] = {
        "vision": ("<<<image>>>", "<<</image>>>"),
    }
    _default_placeholder: Tuple[str, str] = ("<<<media>>>", "<<</media>>>")

    @classmethod
    def register_part_renderer(
        cls,
        part_type: str,
        renderer: RendererFn,
        *,
        modality: Optional[str] = None,
        placeholder: Optional[Tuple[str, str]] = None,
    ) -> None:
        """
        Register or override a renderer for a structured content part.

        Args:
            part_type: The ``type`` field value to handle (case-insensitive).
            renderer: Callable that returns a rendered content part or None to drop it.
            modality: Optional modality flag associated with the part (e.g. ``"vision"``).
            placeholder: Custom placeholder pair (prefix, suffix) used during flattening.
        """
        normalized_part = part_type.lower()
        cls._part_renderers[normalized_part] = renderer
        cls._part_modalities[normalized_part] = modality

        if modality and placeholder:
            cls._modality_placeholders[modality] = placeholder

    @classmethod
    def render(
        cls,
        content: MessageContent,
        variables: Dict[str, Any],
        *,
        supported_modalities: Optional[Mapping[str, bool]] = None,
        template_type: Union[str, PromptType] = PromptType.MUSTACHE,
    ) -> MessageContent:
        """
        Render message content, preserving multimodal parts when supported.

        Args:
            content: Message content definition. Either a plain string or a list of
                OpenAI-style content blocks (dictionaries with ``type`` keys).
            variables: Template variables used when formatting dynamic fields.
            supported_modalities: Mapping of modality flags (e.g. ``{\"vision\": True}``).
            template_type: Rendering mode accepted by :class:`PromptTemplate`.

        Returns:
            The rendered message content using the same structure as the input when
            all modalities are supported. Otherwise, structured content is flattened
            into a newline-separated string with media placeholders.
        """
        modality_flags: Dict[str, bool] = dict(supported_modalities or {})
        normalized_type = cls._normalize_template_type(template_type)

        if isinstance(content, str):
            return cls._render_template_string(content, variables, normalized_type)

        if not isinstance(content, list):
            return str(content)

        if not content:
            return []

        rendered_parts = cls._render_structured_content(
            content=content,
            variables=variables,
            template_type=normalized_type,
        )

        if not rendered_parts:
            return []

        if cls._should_flatten(rendered_parts, modality_flags):
            return cls._flatten_parts_to_text(rendered_parts, modality_flags)

        return rendered_parts

    # ------------------------------------------------------------------
    # Internal helpers
    # ------------------------------------------------------------------

    @staticmethod
    def _normalize_template_type(template_type: Union[str, PromptType]) -> PromptType:
        if isinstance(template_type, PromptType):
            return template_type
        try:
            return PromptType(template_type)
        except ValueError:
            return PromptType.MUSTACHE

    @classmethod
    def _render_structured_content(
        cls,
        content: List[Any],
        variables: Dict[str, Any],
        template_type: PromptType,
    ) -> List[ContentPart]:
        rendered_parts: List[ContentPart] = []

        for part in content:
            if not isinstance(part, dict):
                continue

            part_type = part.get("type", "").lower()
            renderer = cls._part_renderers.get(part_type)
            if renderer is None:
                continue

            rendered_part = renderer(part, variables, template_type)
            if rendered_part is None:
                continue

            rendered_parts.append(rendered_part)

        return rendered_parts

    @staticmethod
    def _render_template_string(
        template: str,
        variables: Dict[str, Any],
        template_type: PromptType,
    ) -> str:
        if not template:
            return ""

        try:
            return PromptTemplate(
                template,
                validate_placeholders=False,
                type=template_type,
            ).format(**variables)
        except Exception:
            # Fall back to the raw template if formatting fails so evaluation keeps running.
            return template

    @staticmethod
    def _render_text_part(
        part: ContentPart, variables: Dict[str, Any], template_type: PromptType
    ) -> Optional[ContentPart]:
        text_template = part.get("text", "")
        rendered_text = MessageContentRenderer._render_template_string(
            text_template, variables, template_type
        )
        return {"type": "text", "text": rendered_text}

    @staticmethod
    def _render_image_url_part(
        part: ContentPart, variables: Dict[str, Any], template_type: PromptType
    ) -> Optional[ContentPart]:
        image_dict = part.get("image_url", {})
        if not isinstance(image_dict, dict):
            return None

        url_template = image_dict.get("url", "")
        # Works with http(s) links as well as base64 data URIs such as data:image/png;base64,...
        rendered_url = MessageContentRenderer._render_template_string(
            url_template, variables, template_type
        )
        if not rendered_url:
            return None

        rendered_image: Dict[str, Any] = {"url": rendered_url}
        if "detail" in image_dict:
            rendered_image["detail"] = image_dict["detail"]

        return {"type": "image_url", "image_url": rendered_image}

    @classmethod
    def _should_flatten(
        cls, parts: List[ContentPart], modality_flags: Mapping[str, bool]
    ) -> bool:
        for part in parts:
            modality = cls._part_modalities.get(part.get("type", "").lower())
            if modality and not modality_flags.get(modality, False):
                return True
        return False

    @classmethod
    def _flatten_parts_to_text(
        cls,
        parts: List[ContentPart],
        modality_flags: Mapping[str, bool],
    ) -> str:
        segments: List[str] = []
        for part in parts:
            part_type = part.get("type", "").lower()

            if part_type == "text":
                text = part.get("text", "")
                if text:
                    segments.append(text)
                continue

            modality = cls._part_modalities.get(part_type)
            if modality and modality_flags.get(modality, False):
                segments.append(str(part))
                continue

            if modality and modality in cls._modality_placeholders:
                prefix, suffix = cls._modality_placeholders[modality]
            else:
                prefix, suffix = cls._default_placeholder
            placeholder_value = cls._extract_placeholder_value(part)
            if placeholder_value:
                segments.append(f"{prefix}{placeholder_value}{suffix}")

        return "\n\n".join(segment for segment in segments if segment)

    @staticmethod
    def _extract_placeholder_value(part: ContentPart) -> str:
        part_type = part.get("type", "").lower()
        if part_type == "image_url":
            image_dict = part.get("image_url", {})
            if isinstance(image_dict, dict):
                return str(image_dict.get("url", "")).strip()
        return str(part)


# Register built-in renderers for text and images (supports PNG/JPEG URLs and base64 data URIs).
MessageContentRenderer.register_part_renderer(
    "text",
    MessageContentRenderer._render_text_part,
    modality=None,
)
MessageContentRenderer.register_part_renderer(
    "image_url",
    MessageContentRenderer._render_image_url_part,
    modality="vision",
    placeholder=MessageContentRenderer._modality_placeholders["vision"],
)

__all__ = ["MessageContentRenderer", "MessageContent"]
