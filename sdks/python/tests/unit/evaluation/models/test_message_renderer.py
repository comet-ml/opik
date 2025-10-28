from typing import Any, Dict

import pytest

from opik.evaluation.models import MessageContentRenderer


class TestRenderMessageContent:
    def test_renders_plain_text(self) -> None:
        rendered = MessageContentRenderer.render(
            content="Hello {{name}}",
            variables={"name": "Opik"},
            supported_modalities={"vision": False},
        )
        assert rendered == "Hello Opik"

    def test_preserves_structured_content_for_vision_models(self) -> None:
        content = [
            {"type": "text", "text": "Describe this image"},
            {"type": "image_url", "image_url": {"url": "{{image_url}}"}},
        ]

        rendered = MessageContentRenderer.render(
            content=content,
            variables={"image_url": "https://example.com/cat.jpg"},
            supported_modalities={"vision": True},
        )

        assert isinstance(rendered, list)
        assert rendered[0]["text"] == "Describe this image"
        assert rendered[1]["image_url"]["url"] == "https://example.com/cat.jpg"

    def test_includes_detail_field_when_present(self) -> None:
        content = [
            {
                "type": "image_url",
                "image_url": {"url": "https://example.com/image.png", "detail": "high"},
            }
        ]

        rendered = MessageContentRenderer.render(
            content=content,
            variables={},
            supported_modalities={"vision": True},
        )

        assert rendered[0]["image_url"]["detail"] == "high"

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

        rendered = MessageContentRenderer.render(
            content=content,
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

        rendered = MessageContentRenderer.render(
            content=content,
            variables={},
            supported_modalities={"vision": False},
        )

        assert isinstance(rendered, str)
        assert "First" in rendered
        assert "Second" in rendered
        assert "https://example.com/one.png" in rendered
        assert rendered.count("<<<image>>>") == 1

    def test_skips_invalid_parts(self) -> None:
        content = [{"type": "text", "text": "ok"}, "bad-part", None]

        rendered = MessageContentRenderer.render(
            content=content,
            variables={},
            supported_modalities={"vision": True},
        )

        assert rendered == [{"type": "text", "text": "ok"}]

    def test_custom_part_registration_allows_future_modalities(self) -> None:
        custom_part = {
            "type": "custom_media",
            "custom_media": {"data": "AAA=", "format": "binary"},
        }

        def _render_custom(
            part: Dict[str, Any], _variables: Dict[str, Any], _template_type: str
        ) -> Dict[str, Any]:
            return part

        MessageContentRenderer.register_part_renderer(
            "custom_media",
            _render_custom,
            modality="custom",
            placeholder=("<<<custom>>>", "<<</custom>>>"),
        )

        rendered = MessageContentRenderer.render(
            content=[custom_part],
            variables={},
            supported_modalities={"vision": True, "custom": False},
        )

        assert isinstance(rendered, str)
        assert "<<<custom>>>" in rendered

        rendered_with_custom = MessageContentRenderer.render(
            content=[custom_part],
            variables={},
            supported_modalities={"vision": True, "custom": True},
        )

        assert isinstance(rendered_with_custom, list)
        assert rendered_with_custom[0]["type"] == "custom_media"
