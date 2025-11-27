from google.adk.models import LlmResponse
from google.genai import types
from opik.integrations.adk.helpers import has_empty_text_part_content


def test_has_empty_text_part_content_no_content():
    assert (
        has_empty_text_part_content(
            LlmResponse(
                content=None,
            )
        )
        is True
    )


def test_has_empty_text_part_content_no_parts():
    assert (
        has_empty_text_part_content(
            LlmResponse(
                content=types.Content(
                    parts=None,
                ),
            )
        )
        is True
    )


def test_has_empty_text_part_content_empty_parts():
    assert (
        has_empty_text_part_content(
            LlmResponse(
                content=types.Content(
                    parts=list(),
                ),
            )
        )
        is True
    )


def test_has_empty_text_part_content_with_parts():
    assert (
        has_empty_text_part_content(
            LlmResponse(
                content=types.Content(
                    parts=[types.Part(text="some text")],
                ),
            )
        )
        is False
    )
