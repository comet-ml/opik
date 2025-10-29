"""
Tools for rendering chat-style prompts with multimodal content.

The template mirrors :class:`PromptTemplate` but works on a list of OpenAI-like
messages. Rendering is handled by a registry of part renderers so additional
modalities can be plugged in without changing the core implementation.
"""

from __future__ import annotations

from typing import (
    Any,
    Callable,
    Dict,
    List,
    Literal,
    Mapping,
    MutableMapping,
    Optional,
    Set,
    Tuple,
    Union,
    cast,
)

from .prompt_template import PromptTemplate
from .types import PromptType

MessageContent = Union[str, List[Dict[str, Any]]]
ContentPart = Dict[str, Any]
RendererFn = Callable[[ContentPart, Dict[str, Any], PromptType], Optional[ContentPart]]
ModalityName = Literal["vision"]
SupportedModalities = Mapping[ModalityName, bool]
ModalitySet = Set[ModalityName]


class ChatContentRendererRegistry:
    """
    Registry that knows how to render structured content parts.
    """

    def __init__(self) -> None:
        self._part_renderers: MutableMapping[str, RendererFn] = {}
        self._part_modalities: MutableMapping[str, Optional[ModalityName]] = {}
        self._modality_placeholders: MutableMapping[ModalityName, Tuple[str, str]] = {
            "vision": ("<<<image>>>", "<<</image>>>"),
        }
        self._default_placeholder: Tuple[str, str] = ("<<<media>>>", "<<</media>>>")
        self._placeholder_value_limit = 500

    def register_part_renderer(
        self,
        part_type: str,
        renderer: RendererFn,
        *,
        modality: Optional[ModalityName] = None,
        placeholder: Optional[Tuple[str, str]] = None,
    ) -> None:
        """
        Register or override the renderer responsible for a structured content part.
        """
        normalized_part = part_type.lower()
        self._part_renderers[normalized_part] = renderer
        self._part_modalities[normalized_part] = modality

        if modality and placeholder:
            self._modality_placeholders[modality] = placeholder

    def render_content(
        self,
        content: MessageContent,
        variables: Dict[str, Any],
        template_type: PromptType,
        *,
        supported_modalities: Optional[SupportedModalities] = None,
    ) -> MessageContent:
        if supported_modalities is None:
            modality_flags: Dict[str, bool] = {}
        else:
            modality_flags = {
                modality: bool(is_supported)
                for modality, is_supported in supported_modalities.items()
            }

        if isinstance(content, str):
            return _render_template_string(content, variables, template_type)

        if not isinstance(content, list):
            return str(content)

        if not content:
            return []

        rendered_parts = self._render_structured_content(
            content=content,
            variables=variables,
            template_type=template_type,
        )

        if not rendered_parts:
            return []

        if self._should_flatten(rendered_parts, modality_flags):
            return self._flatten_parts_to_text(rendered_parts, modality_flags)

        return rendered_parts

    def normalize_template_type(
        self, template_type: Union[str, PromptType]
    ) -> PromptType:
        if isinstance(template_type, PromptType):
            return template_type

        try:
            return PromptType(template_type)
        except ValueError:
            return PromptType.MUSTACHE

    def infer_modalities(self, content: MessageContent) -> ModalitySet:
        """
        Return the set of modalities referenced by the structured content.
        """
        if not isinstance(content, list):
            return set()

        modalities: ModalitySet = set()
        for part in content:
            if not isinstance(part, dict):
                continue
            part_type = part.get("type", "").lower()
            modality = self._part_modalities.get(part_type)
            if modality:
                modalities.add(modality)
        return modalities

    def _render_structured_content(
        self,
        content: List[Any],
        variables: Dict[str, Any],
        template_type: PromptType,
    ) -> List[ContentPart]:
        rendered_parts: List[ContentPart] = []

        for part in content:
            if not isinstance(part, dict):
                continue

            part_type = part.get("type", "").lower()
            renderer = self._part_renderers.get(part_type)
            if renderer is None:
                continue

            rendered_part = renderer(part, variables, template_type)
            if rendered_part is None:
                continue

            rendered_parts.append(rendered_part)

        return rendered_parts

    def _should_flatten(
        self, parts: List[ContentPart], modality_flags: Mapping[str, bool]
    ) -> bool:
        for part in parts:
            modality = self._part_modalities.get(part.get("type", "").lower())
            if modality and not modality_flags.get(modality, False):
                return True
        return False

    def _flatten_parts_to_text(
        self,
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

            modality = self._part_modalities.get(part_type)
            if modality and modality_flags.get(modality, False):
                segments.append(str(part))
                continue

            if modality and modality in self._modality_placeholders:
                prefix, suffix = self._modality_placeholders[modality]
            else:
                prefix, suffix = self._default_placeholder

            placeholder_value = self._extract_placeholder_value(part)
            if placeholder_value:
                truncated = self._truncate_placeholder_value(placeholder_value)
                segments.append(f"{prefix}{truncated}{suffix}")

        return "\n\n".join(segment for segment in segments if segment)

    @staticmethod
    def _extract_placeholder_value(part: ContentPart) -> str:
        part_type = part.get("type", "").lower()
        if part_type == "image_url":
            image_dict = part.get("image_url", {})
            if isinstance(image_dict, dict):
                return str(image_dict.get("url", "")).strip()
        return str(part)

    def _truncate_placeholder_value(self, value: str) -> str:
        if len(value) <= self._placeholder_value_limit:
            return value
        if self._placeholder_value_limit <= 3:
            return value[: self._placeholder_value_limit]
        return value[: self._placeholder_value_limit - 3] + "..."


class ChatPromptTemplate:
    """
    Prompt template for chat-style prompts with multimodal content.
    """

    def __init__(
        self,
        messages: List[Dict[str, MessageContent]],
        template_type: PromptType = PromptType.MUSTACHE,
        *,
        registry: Optional[ChatContentRendererRegistry] = None,
    ) -> None:
        self._messages = messages
        self._template_type = template_type
        self._registry = registry or DEFAULT_CHAT_RENDERER_REGISTRY

    @property
    def messages(self) -> List[Dict[str, MessageContent]]:
        return self._messages

    def required_modalities(self) -> ModalitySet:
        """
        Return the union of modalities referenced across all template messages.
        """
        required: ModalitySet = set()
        for message in self._messages:
            content = cast(MessageContent, message.get("content", ""))
            required.update(self._registry.infer_modalities(content))
        return required

    def format(
        self,
        variables: Dict[str, Any],
        supported_modalities: Optional[SupportedModalities] = None,
        *,
        template_type: Optional[Union[str, PromptType]] = None,
    ) -> List[Dict[str, MessageContent]]:
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
        rendered_messages: List[Dict[str, MessageContent]] = []

        for message in self._messages:
            role = message.get("role")
            if role is None:
                continue

            content = message.get("content", "")
            rendered_content = self._registry.render_content(
                content=content,
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


DEFAULT_CHAT_RENDERER_REGISTRY = ChatContentRendererRegistry()


def register_default_chat_part_renderer(
    part_type: str,
    renderer: RendererFn,
    *,
    modality: Optional[ModalityName] = None,
    placeholder: Optional[Tuple[str, str]] = None,
) -> None:
    DEFAULT_CHAT_RENDERER_REGISTRY.register_part_renderer(
        part_type,
        renderer,
        modality=modality,
        placeholder=placeholder,
    )


def render_text_part(
    part: ContentPart, variables: Dict[str, Any], template_type: PromptType
) -> Optional[ContentPart]:
    text_template = part.get("text", "")
    rendered_text = _render_template_string(text_template, variables, template_type)
    return {"type": "text", "text": rendered_text}


def render_image_url_part(
    part: ContentPart, variables: Dict[str, Any], template_type: PromptType
) -> Optional[ContentPart]:
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


register_default_chat_part_renderer("text", render_text_part)
register_default_chat_part_renderer(
    "image_url",
    render_image_url_part,
    modality="vision",
    placeholder=("<<<image>>>", "<<</image>>>"),
)


__all__ = [
    "ChatPromptTemplate",
    "ChatContentRendererRegistry",
    "MessageContent",
    "ContentPart",
    "RendererFn",
    "register_default_chat_part_renderer",
    "ModalityName",
    "SupportedModalities",
]
