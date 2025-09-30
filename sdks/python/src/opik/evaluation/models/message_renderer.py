"""
Message content rendering for LLM evaluation with multimodal support.

This module handles rendering of message content including text and images,
with support for both vision and non-vision models.
"""

from typing import Any, Dict, List, Union
from opik.api_objects.prompt.prompt_template import PromptTemplate

# Placeholder format for image references in text-only mode
IMAGE_PLACEHOLDER_START = "<<<image>>>"
IMAGE_PLACEHOLDER_END = "<<</image>>>"


MessageContentType = Union[str, List[Dict[str, Any]]]


def render_message_content(
    content: MessageContentType,
    variables: Dict[str, Any],
    supports_vision: bool,
    template_type: str = "mustache",
) -> MessageContentType:
    """
    Render message content with variable substitution, handling both text and structured (multimodal) content.

    Args:
        content: The message content - either a string or a list of content parts
        variables: Template variables to substitute
        supports_vision: Whether the target model supports vision (images)
        template_type: Type of template syntax ("mustache" or "f-string")

    Returns:
        Rendered content in the same format as input if vision is supported,
        otherwise flattened to a string with image placeholders

    Examples:
        # Text-only content
        >>> render_message_content(
        ...     "Hello {{name}}",
        ...     {"name": "World"},
        ...     supports_vision=False
        ... )
        "Hello World"

        # Structured content for vision model
        >>> render_message_content(
        ...     [
        ...         {"type": "text", "text": "What's in {{image_var}}?"},
        ...         {"type": "image_url", "image_url": {"url": "{{image_var}}"}}
        ...     ],
        ...     {"image_var": "https://example.com/cat.jpg"},
        ...     supports_vision=True
        ... )
        [
            {"type": "text", "text": "What's in https://example.com/cat.jpg?"},
            {"type": "image_url", "image_url": {"url": "https://example.com/cat.jpg"}}
        ]

        # Structured content for non-vision model (flattened)
        >>> render_message_content(
        ...     [
        ...         {"type": "text", "text": "Analyze this"},
        ...         {"type": "image_url", "image_url": {"url": "https://example.com/cat.jpg"}}
        ...     ],
        ...     {},
        ...     supports_vision=False
        ... )
        "Analyze this\n\n<<<image>>>https://example.com/cat.jpg<<</image>>>"
    """
    # Simple string content - just render template
    if isinstance(content, str):
        return _render_string_template(content, variables, template_type)

    # Structured content (list of parts)
    if not isinstance(content, list):
        return str(content)

    # Handle empty list - preserve as structured content
    if len(content) == 0:
        return []

    # Check if it's OpenAI-style structured content
    if not _is_structured_content(content):
        return str(content)

    # Render each part with template substitution
    rendered_parts = []
    for part in content:
        if not isinstance(part, dict):
            continue

        part_type = part.get("type", "").lower()

        if part_type == "text":
            text_template = part.get("text", "")
            rendered_text = _render_string_template(text_template, variables, template_type)
            rendered_parts.append({
                "type": "text",
                "text": rendered_text,
            })

        elif part_type == "image_url":
            image_dict = part.get("image_url", {})
            if not isinstance(image_dict, dict):
                continue

            url_template = image_dict.get("url", "")
            rendered_url = _render_string_template(url_template, variables, template_type)

            rendered_image = {"url": rendered_url}
            if "detail" in image_dict:
                rendered_image["detail"] = image_dict["detail"]

            rendered_parts.append({
                "type": "image_url",
                "image_url": rendered_image,
            })

    # If model doesn't support vision, flatten to text with placeholders
    if not supports_vision:
        return _flatten_to_text(rendered_parts)

    return rendered_parts


def _render_string_template(
    template: str,
    variables: Dict[str, Any],
    template_type: str,
) -> str:
    """Render a string template with variables."""
    if not template:
        return ""

    try:
        return PromptTemplate(
            template,
            validate_placeholders=False,
            type=template_type,
        ).format(**variables)
    except Exception:
        # If template rendering fails, return original
        return template


def _is_structured_content(content: List[Any]) -> bool:
    """Check if content is OpenAI-style structured content (array of parts with 'type')."""
    if not content:
        return False

    # Check if at least one item has a 'type' field
    for item in content:
        if isinstance(item, dict) and "type" in item:
            return True

    return False


def _flatten_to_text(parts: List[Dict[str, Any]]) -> str:
    """
    Flatten structured content to a text string with image placeholders.

    This is used when a non-vision model receives structured content.
    """
    text_segments = []

    for part in parts:
        part_type = part.get("type", "").lower()

        if part_type == "text":
            text = part.get("text", "")
            if text:
                text_segments.append(text)

        elif part_type == "image_url":
            url = part.get("image_url", {}).get("url", "")
            if url:
                placeholder = f"{IMAGE_PLACEHOLDER_START}{url}{IMAGE_PLACEHOLDER_END}"
                text_segments.append(placeholder)

    return "\n\n".join(text_segments)
