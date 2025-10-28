from opik.evaluation.models import message_renderer


class TestRenderMessageContent:
    def test_renders_plain_text(self):
        rendered = message_renderer.render_message_content(
            content="Hello {{name}}",
            variables={"name": "Opik"},
            supports_vision=False,
        )
        assert rendered == "Hello Opik"

    def test_preserves_structured_content_for_vision_models(self):
        content = [
            {"type": "text", "text": "Describe this image"},
            {"type": "image_url", "image_url": {"url": "{{image_url}}"}},
        ]

        rendered = message_renderer.render_message_content(
            content=content,
            variables={"image_url": "https://example.com/cat.jpg"},
            supports_vision=True,
        )

        assert isinstance(rendered, list)
        assert rendered[0]["text"] == "Describe this image"
        assert rendered[1]["image_url"]["url"] == "https://example.com/cat.jpg"

    def test_includes_detail_field_when_present(self):
        content = [
            {
                "type": "image_url",
                "image_url": {"url": "https://example.com/image.png", "detail": "high"},
            }
        ]

        rendered = message_renderer.render_message_content(
            content=content,
            variables={},
            supports_vision=True,
        )

        assert rendered[0]["image_url"]["detail"] == "high"

    def test_supports_base64_image_urls(self):
        data_url = "data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAUA"
        content = [
            {"type": "text", "text": "Inline data"},
            {"type": "image_url", "image_url": {"url": data_url}},
        ]

        rendered = message_renderer.render_message_content(
            content=content,
            variables={},
            supports_vision=True,
        )

        assert rendered[1]["image_url"]["url"] == data_url

    def test_flattens_structured_content_when_vision_disabled(self):
        content = [
            {"type": "text", "text": "First"},
            {"type": "image_url", "image_url": {"url": "https://example.com/one.png"}},
            {"type": "text", "text": "Second"},
        ]

        rendered = message_renderer.render_message_content(
            content=content,
            variables={},
            supports_vision=False,
        )

        assert isinstance(rendered, str)
        assert "First" in rendered
        assert "Second" in rendered
        assert "https://example.com/one.png" in rendered

    def test_skips_invalid_parts(self):
        content = [{"type": "text", "text": "ok"}, "bad-part", None]

        rendered = message_renderer.render_message_content(
            content=content,
            variables={},
            supports_vision=True,
        )

        assert rendered == [{"type": "text", "text": "ok"}]


class TestHelpers:
    def test_flatten_joins_with_double_newlines(self):
        parts = [
            {"type": "text", "text": "A"},
            {"type": "image_url", "image_url": {"url": "https://example.com/a.png"}},
            {"type": "text", "text": "B"},
        ]

        flattened = message_renderer._flatten_parts_to_text(parts)  # type: ignore[attr-defined]

        lines = flattened.split("\n\n")
        assert len(lines) == 3
        assert "<<<image>>>" in lines[1]
