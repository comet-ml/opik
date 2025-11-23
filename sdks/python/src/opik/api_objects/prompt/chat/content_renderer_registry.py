from typing import Any, Dict, List, Mapping, MutableMapping, Optional, Tuple, Union

from .. import types as prompt_types


class ChatContentRendererRegistry:
    """
    Registry that knows how to render structured content parts.
    """

    def __init__(self) -> None:
        self._part_renderers: MutableMapping[str, prompt_types.RendererFn] = {}
        self._part_modalities: MutableMapping[
            str, Optional[prompt_types.ModalityName]
        ] = {}
        self._modality_placeholders: MutableMapping[
            prompt_types.ModalityName, Tuple[str, str]
        ] = {
            "vision": ("<<<image>>>", "<<</image>>>"),
            "video": ("<<<video>>>", "<<</video>>>"),
        }
        self._default_placeholder: Tuple[str, str] = ("<<<media>>>", "<<</media>>>")
        self._placeholder_value_limit = 500

    def register_part_renderer(
        self,
        part_type: str,
        renderer: prompt_types.RendererFn,
        *,
        modality: Optional[prompt_types.ModalityName] = None,
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
        content: prompt_types.MessageContent,
        variables: Dict[str, Any],
        template_type: prompt_types.PromptType,
        *,
        supported_modalities: Optional[prompt_types.SupportedModalities] = None,
    ) -> prompt_types.MessageContent:
        if supported_modalities is None:
            modality_flags: Dict[str, bool] = {}
        else:
            modality_flags = {
                modality: bool(is_supported)
                for modality, is_supported in supported_modalities.items()
            }

        if isinstance(content, str):
            # Resolved in ChatPromptTemplate
            return content

        if not isinstance(content, list):
            return str(content)

        if not content:
            return []

        rendered_parts = self._render_structured_content(
            content=content, variables=variables, template_type=template_type
        )

        if not rendered_parts:
            return []

        if self._should_flatten(rendered_parts, modality_flags):
            return self._flatten_parts_to_text(rendered_parts, modality_flags)

        return rendered_parts

    def normalize_template_type(
        self, template_type: Union[str, prompt_types.PromptType]
    ) -> prompt_types.PromptType:
        if isinstance(template_type, prompt_types.PromptType):
            return template_type
        try:
            return prompt_types.PromptType(template_type)
        except ValueError:
            return prompt_types.PromptType.MUSTACHE

    def infer_modalities(
        self, content: prompt_types.MessageContent
    ) -> set[prompt_types.ModalityName]:
        if not isinstance(content, list):
            return set()
        modalities: set[prompt_types.ModalityName] = set()
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
        template_type: prompt_types.PromptType,
    ) -> List[prompt_types.ContentPart]:
        rendered_parts: List[prompt_types.ContentPart] = []
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
        self, parts: List[prompt_types.ContentPart], modality_flags: Mapping[str, bool]
    ) -> bool:
        for part in parts:
            modality = self._part_modalities.get(part.get("type", "").lower())
            if modality and not modality_flags.get(modality, False):
                return True
        return False

    def _flatten_parts_to_text(
        self, parts: List[prompt_types.ContentPart], modality_flags: Mapping[str, bool]
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
    def _extract_placeholder_value(part: prompt_types.ContentPart) -> str:
        part_type = part.get("type", "").lower()
        if part_type == "image_url":
            image_dict = part.get("image_url", {})
            if isinstance(image_dict, dict):
                return str(image_dict.get("url", "")).strip()
        if part_type == "video_url":
            video_dict = part.get("video_url", {})
            if isinstance(video_dict, dict):
                return str(video_dict.get("url", "")).strip()
        return str(part)

    def _truncate_placeholder_value(self, value: str) -> str:
        if len(value) <= self._placeholder_value_limit:
            return value
        if self._placeholder_value_limit <= 3:
            return value[: self._placeholder_value_limit]
        return value[: self._placeholder_value_limit - 3] + "..."


DEFAULT_CHAT_RENDERER_REGISTRY = ChatContentRendererRegistry()


def register_default_chat_part_renderer(
    part_type: str,
    renderer: prompt_types.RendererFn,
    *,
    modality: Optional[prompt_types.ModalityName] = None,
    placeholder: Optional[Tuple[str, str]] = None,
) -> None:
    DEFAULT_CHAT_RENDERER_REGISTRY.register_part_renderer(
        part_type,
        renderer,
        modality=modality,
        placeholder=placeholder,
    )


__all__ = [
    "ChatContentRendererRegistry",
    "DEFAULT_CHAT_RENDERER_REGISTRY",
    "register_default_chat_part_renderer",
]
