import random
import string
from typing import Any, Dict, Optional

import pytest

import opik.api_objects.prompt.chat_prompt_template as chat_prompt_template
from opik.api_objects.prompt.chat_prompt_template import ChatPromptTemplate
from opik.api_objects.prompt.chat_content_renderer_registry import (
    ChatContentRendererRegistry,
)


def _render_content(
    content: Any,
    *,
    variables: Optional[Dict[str, Any]] = None,
    supported_modalities: Optional[Dict[str, bool]] = None,
    registry: Optional[ChatContentRendererRegistry] = None,
) -> Any:
    template = ChatPromptTemplate(
        messages=[{"role": "user", "content": content}],
        registry=registry,
    )
    rendered = template.format(
        variables=variables or {},
        supported_modalities=supported_modalities,
    )
    assert len(rendered) == 1
    return rendered[0]["content"]


class TestChatPromptTemplate:
    def test_renders_plain_text(self) -> None:
        rendered = _render_content(
            "Hello {{name}}",
            variables={"name": "Opik"},
            supported_modalities={"vision": False},
        )
        assert rendered == "Hello Opik"

    def test_preserves_structured_content_for_vision_models(self) -> None:
        content = [
            {"type": "text", "text": "Describe this image"},
            {"type": "image_url", "image_url": {"url": "{{image_url}}"}},
        ]

        rendered = _render_content(
            content,
            variables={"image_url": "https://example.com/cat.jpg"},
            supported_modalities={"vision": True, "video": True},
        )

        assert isinstance(rendered, list)
        assert rendered[0]["text"] == "Describe this image"
        assert rendered[1]["image_url"]["url"] == "https://example.com/cat.jpg"

    def test_preserves_structured_content_for_video_models(self) -> None:
        content = [
            {"type": "text", "text": "Watch this video"},
            {"type": "video_url", "video_url": {"url": "{{video_url}}"}},
        ]

        rendered = _render_content(
            content,
            variables={"video_url": "https://example.com/clip.mp4"},
            supported_modalities={"vision": True, "video": True},
        )

        assert isinstance(rendered, list)
        assert rendered[0]["text"] == "Watch this video"
        assert rendered[1]["video_url"]["url"] == "https://example.com/clip.mp4"

    @pytest.mark.parametrize("detail", ["low", "high"])
    def test_includes_detail_field_when_present(self, detail: str) -> None:
        content = [
            {
                "type": "image_url",
                "image_url": {
                    "url": "https://example.com/image.png",
                    "detail": detail,
                },
            }
        ]

        rendered = _render_content(
            content,
            variables={},
            supported_modalities={"vision": True},
        )

        assert rendered[0]["image_url"]["detail"] == detail

    @pytest.mark.parametrize(
        "data_url_prefix",
        ["data:image/png;base64,", "data:image/jpeg;base64,"],
    )
    def test_supports_base64_image_urls(self, data_url_prefix: str) -> None:
        data_url = f"{data_url_prefix}iVBORw0KGgoAAAANSUhEUgAAAAUA"
        content = [
            {"type": "text", "text": "Inline data"},
            {"type": "image_url", "image_url": {"url": data_url}},
        ]

        rendered = _render_content(
            content,
            variables={},
            supported_modalities={"vision": True},
        )

        assert rendered[1]["image_url"]["url"] == data_url

    def test_flattens_structured_content_when_vision_disabled(self) -> None:
        content = [
            {"type": "text", "text": "First"},
            {"type": "image_url", "image_url": {"url": "https://example.com/one.png"}},
            {"type": "text", "text": "Second"},
        ]

        rendered = _render_content(
            content,
            variables={},
            supported_modalities={"vision": False, "video": False},
        )

        assert isinstance(rendered, str)
        assert "First" in rendered
        assert "Second" in rendered
        assert "https://example.com/one.png" in rendered
        assert rendered.count("<<<image>>>") == 1

    def test_flattens_structured_video_when_video_disabled(self) -> None:
        content = [
            {"type": "text", "text": "Context"},
            {"type": "video_url", "video_url": {"url": "https://example.com/clip.mp4"}},
        ]

        rendered = _render_content(
            content,
            variables={},
            supported_modalities={"vision": True, "video": False},
        )

        assert isinstance(rendered, str)
        assert "Context" in rendered
        assert "<<<video>>>" in rendered

    def test_flattened_placeholder_truncates_large_base64(self) -> None:
        random_payload = "".join(
            random.choices(string.ascii_letters + string.digits + "+/", k=700)
        )
        data_url = f"data:image/png;base64,{random_payload}"
        content = [
            {"type": "image_url", "image_url": {"url": data_url}},
        ]

        rendered = _render_content(
            content,
            variables={},
            supported_modalities={"vision": False},
        )

        assert isinstance(rendered, str)
        assert "<<<image>>>" in rendered
        inner = rendered.split("<<<image>>>")[1].split("<<</image>>>")[0]
        assert len(inner) <= 500
        assert inner.endswith("...")

    def test_skips_invalid_parts(self) -> None:
        content = [{"type": "text", "text": "ok"}, "bad-part", None]

        rendered = _render_content(
            content,
            variables={},
            supported_modalities={"vision": True},
        )

        assert rendered == [{"type": "text", "text": "ok"}]

    def test_custom_part_registration_allows_new_parts(self) -> None:
        registry = ChatContentRendererRegistry()
        registry.register_part_renderer("text", chat_prompt_template.render_text_part)
        registry.register_part_renderer(
            "image_url",
            chat_prompt_template.render_image_url_part,
            modality="vision",
            placeholder=("<<<image>>>", "<<</image>>>"),
        )

        custom_part = {"type": "thumbnail", "image_url": {"url": "{{thumb_url}}"}}

        def _render_thumbnail(
            part: Dict[str, Any], variables: Dict[str, Any], template_type: Any
        ) -> Dict[str, Any]:
            rendered = chat_prompt_template.render_image_url_part(
                part, variables, template_type
            )
            assert rendered is not None
            return {"type": "thumbnail", "image_url": rendered["image_url"]}

        registry.register_part_renderer(
            "thumbnail",
            _render_thumbnail,
            modality="vision",
        )

        rendered = _render_content(
            [custom_part],
            variables={"thumb_url": "https://example.com/thumb.png"},
            supported_modalities={"vision": True, "video": True},
            registry=registry,
        )

        assert rendered[0]["type"] == "thumbnail"
        assert rendered[0]["image_url"]["url"] == "https://example.com/thumb.png"

        flattened = _render_content(
            [custom_part],
            variables={"thumb_url": "https://example.com/thumb.png"},
            supported_modalities={"vision": False, "video": False},
            registry=registry,
        )

        assert isinstance(flattened, str)
        assert "thumbnail" in flattened

    def test_required_modalities_detects_vision(self) -> None:
        template = ChatPromptTemplate(
            messages=[
                {
                    "role": "user",
                    "content": [
                        {"type": "text", "text": "Describe the image"},
                        {"type": "image_url", "image_url": {"url": "{{image_url}}"}},
                    ],
                }
            ]
        )

        assert template.required_modalities() == {"vision"}

    def test_required_modalities_detects_video(self) -> None:
        template = ChatPromptTemplate(
            messages=[
                {
                    "role": "user",
                    "content": [
                        {"type": "text", "text": "Summarize the video"},
                        {"type": "video_url", "video_url": {"url": "{{video_url}}"}},
                    ],
                }
            ]
        )

        assert template.required_modalities() == {"video"}
