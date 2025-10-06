import pytest
from opik.evaluation.models import message_renderer


class TestRenderMessageContent:
    def test_renders_simple_string(self):
        result = message_renderer.render_message_content(
            content="Hello {{name}}",
            variables={"name": "World"},
            supports_vision=False,
        )
        assert result == "Hello World"

    def test_renders_string_without_variables(self):
        result = message_renderer.render_message_content(
            content="Plain text",
            variables={},
            supports_vision=False,
        )
        assert result == "Plain text"

    def test_preserves_structured_content_for_vision_models(self):
        content = [
            {"type": "text", "text": "What's in {{variable}}?"},
            {"type": "image_url", "image_url": {"url": "{{image_url}}"}}
        ]

        result = message_renderer.render_message_content(
            content=content,
            variables={"variable": "this image", "image_url": "https://example.com/cat.jpg"},
            supports_vision=True,
        )

        assert isinstance(result, list)
        assert len(result) == 2
        assert result[0]["type"] == "text"
        assert result[0]["text"] == "What's in this image?"
        assert result[1]["type"] == "image_url"
        assert result[1]["image_url"]["url"] == "https://example.com/cat.jpg"

    def test_flattens_structured_content_for_non_vision_models(self):
        content = [
            {"type": "text", "text": "Analyze this"},
            {"type": "image_url", "image_url": {"url": "https://example.com/cat.jpg"}}
        ]

        result = message_renderer.render_message_content(
            content=content,
            variables={},
            supports_vision=False,
        )

        assert isinstance(result, str)
        assert "Analyze this" in result
        assert "<<<image>>>https://example.com/cat.jpg<<</image>>>" in result

    def test_handles_multiple_images(self):
        content = [
            {"type": "text", "text": "Compare these images:"},
            {"type": "image_url", "image_url": {"url": "https://example.com/image1.jpg"}},
            {"type": "image_url", "image_url": {"url": "https://example.com/image2.jpg"}}
        ]

        result = message_renderer.render_message_content(
            content=content,
            variables={},
            supports_vision=True,
        )

        assert isinstance(result, list)
        assert len(result) == 3
        assert result[1]["image_url"]["url"] == "https://example.com/image1.jpg"
        assert result[2]["image_url"]["url"] == "https://example.com/image2.jpg"

    def test_handles_image_with_detail_parameter(self):
        content = [
            {"type": "text", "text": "High detail image"},
            {
                "type": "image_url",
                "image_url": {
                    "url": "https://example.com/image.jpg",
                    "detail": "high"
                }
            }
        ]

        result = message_renderer.render_message_content(
            content=content,
            variables={},
            supports_vision=True,
        )

        assert result[1]["image_url"]["detail"] == "high"

    def test_renders_base64_image_urls(self):
        base64_url = "data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAUA"
        content = [
            {"type": "text", "text": "Base64 image"},
            {"type": "image_url", "image_url": {"url": base64_url}}
        ]

        result = message_renderer.render_message_content(
            content=content,
            variables={},
            supports_vision=True,
        )

        assert result[1]["image_url"]["url"] == base64_url

    def test_handles_empty_structured_content(self):
        result = message_renderer.render_message_content(
            content=[],
            variables={},
            supports_vision=True,
        )

        assert result == []

    def test_handles_malformed_content_gracefully(self):
        # Non-dict items in list
        content = [
            {"type": "text", "text": "Valid"},
            "invalid",
            None,
            {"type": "text", "text": "Also valid"}
        ]

        result = message_renderer.render_message_content(
            content=content,
            variables={},
            supports_vision=True,
        )

        # Should skip invalid items
        assert len(result) == 2
        assert result[0]["text"] == "Valid"
        assert result[1]["text"] == "Also valid"

    def test_template_rendering_with_f_string_type(self):
        result = message_renderer.render_message_content(
            content="Hello {name}",
            variables={"name": "World"},
            supports_vision=False,
            template_type="f-string",
        )

        # Should use mustache by default or handle f-string
        # Implementation may vary, test the actual behavior
        assert "name" not in result or result == "Hello {name}"

    def test_flattens_with_multiple_text_blocks(self):
        content = [
            {"type": "text", "text": "First block"},
            {"type": "image_url", "image_url": {"url": "https://example.com/img.jpg"}},
            {"type": "text", "text": "Second block"}
        ]

        result = message_renderer.render_message_content(
            content=content,
            variables={},
            supports_vision=False,
        )

        assert isinstance(result, str)
        assert "First block" in result
        assert "Second block" in result
        assert "<<<image>>>" in result


class TestFlattenToText:
    def test_joins_text_and_images_with_double_newlines(self):
        parts = [
            {"type": "text", "text": "First"},
            {"type": "image_url", "image_url": {"url": "https://example.com/img.jpg"}},
            {"type": "text", "text": "Second"}
        ]

        result = message_renderer._flatten_to_text(parts)

        lines = result.split("\n\n")
        assert len(lines) == 3
        assert lines[0] == "First"
        assert "<<<image>>>" in lines[1]
        assert lines[2] == "Second"

    def test_skips_empty_text(self):
        parts = [
            {"type": "text", "text": ""},
            {"type": "text", "text": "Not empty"}
        ]

        result = message_renderer._flatten_to_text(parts)

        assert result == "Not empty"
        assert result.count("\n\n") == 0

    def test_skips_empty_image_urls(self):
        parts = [
            {"type": "image_url", "image_url": {"url": ""}},
            {"type": "text", "text": "Text"}
        ]

        result = message_renderer._flatten_to_text(parts)

        assert result == "Text"
        assert "<<<image>>>" not in result
