from opik.message_processing import image_support


def test_supports_image_input_matches_keywords():
    assert image_support.supports_image_input("gpt-4o-mini")
    assert image_support.supports_image_input("awesome-vision-model")
    assert image_support.supports_image_input("o1-preview")


def test_supports_image_input_negative_case():
    assert not image_support.supports_image_input("text-davinci-003")
    assert not image_support.supports_image_input(None)


def test_flatten_multimodal_content_uses_placeholder():
    value = [
        {"type": "text", "text": "Hi"},
        {"type": "image_url", "image_url": {"url": "https://example.com/cat.png"}},
    ]

    flattened = image_support.flatten_multimodal_content(value)

    assert flattened == "Hi\n\n<<<image>>>https://example.com/cat.png<<<\\/image>>>"
