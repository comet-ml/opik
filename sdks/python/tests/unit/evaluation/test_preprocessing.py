from opik.evaluation.preprocessing import normalize_text, ASCII_NORMALIZER


def test_normalize_text_defaults():
    text = "Héllo   World!"
    normalized = normalize_text(text)
    assert normalized == "héllo world!"


def test_normalize_text_with_options():
    text = "Café 😊"
    normalized = normalize_text(
        text,
        lowercase=True,
        strip_accents=True,
        keep_emoji=False,
        remove_punctuation=True,
    )
    assert normalized == "cafe"


def test_ascii_normalizer():
    text = "Olá Mundo 😊"
    assert ASCII_NORMALIZER(text) == "ola mundo"
